package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:agent;DB_CLOSE_DELAY=-1")
class AgentHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @MockitoBean InvestigationModel model;
    @MockitoSpyBean FixtureOrderLogs logs;
    @MockitoSpyBean FixtureOrderSources orders;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    ResponseEntity<JsonNode> call(String path, String actor,Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(actor);
        return http.exchange(path,body == null ? HttpMethod.GET : HttpMethod.POST,
                new HttpEntity<>(body,headers),JsonNode.class);
    }

    String endpoint(String order) {
        var response=call("/cases","demo-ops-a",Map.of("orderId",order));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getHeaders().getLocation().getPath()+"/investigations";
    }

    JsonNode run(String path) {
        return call(path,"demo-ops-a",Map.of("question","第一次回传为何失败？")).getBody();
    }

    InvestigationModel.Decision action(String name,Map<String,?> args) {
        return new InvestigationModel.Decision(name,json.valueToTree(args),12);
    }

    Map<String,String> window(String source) {
        return new HashMap<>(Map.of("source",source,"from","2026-11-18T02:00:00Z","until","2026-11-18T03:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings={"mapping","timeout","missing"})
    void tracesOriginalAttemptAndReadsBackScopedResult(String scenario) {
        String path=endpoint(scenario.equals("missing") ? "123456":"123457");
        if (scenario.equals("timeout")) {
            doAnswer(inv -> {
                OrderLogSource.Page page=(OrderLogSource.Page)inv.callRealMethod();
                var changed=page.events().stream().map(e -> new OrderLogSource.Event(e.id(),e.source(),
                        e.requestRef(),e.time(),"等待数据库响应超时，最终状态未知")).toList();
                return new OrderLogSource.Page(false,false,changed);
            }).when(logs).search(anyString(),anyString(),eq("POS_WRITER"),any(),any(),eq("PUSH-01"),anyInt());
        }
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx=inv.getArgument(0);
            if (ctx.feedback().isEmpty()) return action("searchOrderLogs",window("GATEWAY"));
            var anchor=ctx.evidence().stream().filter(e->e.kind().equals("LOG") && "PUSH-01".equals(e.requestRef()))
                    .findFirst().orElseThrow();
            if (ctx.feedback().size()==1) {
                var args=window("POS_WRITER"); args.put("anchor",anchor.id().toString());
                return action("followLogReference",args);
            }
            var last=ctx.evidence().stream().filter(e -> e.source().equals("POS_WRITER") && e.kind().equals("LOG"))
                    .findFirst();
            return action("finish",Map.of("answer",last.map(CaseEvidence.Item::excerpt).orElse("仅确认接收，原因未知"),
                    "citations",List.of(anchor.id(),last.orElse(anchor).id())));
        });
        var result=run(path);
        assertThat(result.path("status").asText()).isEqualTo("FINISHED");
        assertThat(result.path("steps").findValuesAsText("action"))
                .containsExactly("searchOrderLogs","followLogReference","finish");
        assertThat(result.path("answer").asText()).contains(switch(scenario){
            case "mapping" -> "门店映射"; case "timeout" -> "超时"; default -> "无法确认";});
        var url=path+"/"+result.path("id").asText();
        assertThat(call(url,"demo-ops-a",null).getBody()).isEqualTo(result);
        assertThat(call(url,"demo-ops-b",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void followUpReusesOnlyRepairRequestAndRefreshesCurrentPos() {
        var path=endpoint("123457");
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx=inv.getArgument(0);
            if (ctx.previousTurn().isEmpty() && ctx.exchanges().isEmpty())
                return action("searchOrderLogs",window("GATEWAY"));
            String ref=ctx.previousTurn().isEmpty() ? "PUSH-01" : "REPAIR-02";
            if (!ctx.previousTurn().isEmpty())
                assertThat(ctx.evidence()).noneMatch(e -> "PUSH-01".equals(e.requestRef()) || e.kind().equals("SNAPSHOT"));
            var anchor=ctx.evidence().stream().filter(e -> e.kind().equals("LOG") && e.source().equals("GATEWAY")
                    && ref.equals(e.requestRef())).findFirst().orElseThrow();
            var result=ctx.evidence().stream().filter(e -> e.kind().equals("LOG") && e.source().equals("POS_WRITER")).findFirst();
            if (result.isEmpty()) {
                var args=window("POS_WRITER"); args.put("anchor",anchor.id().toString());
                return action("followLogReference",args);
            }
            return action("finish",Map.of("answer",result.get().excerpt(),"citations",List.of(anchor.id(),result.get().id())));
        });
        var first=run(path);
        var follow=path+"/"+first.get("id").asText()+"/follow-ups";
        assertThat(call(follow,"demo-ops-b",Map.of("question","后来呢")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var second=call(follow,"demo-ops-a-next",Map.of("question","那后来补进去的那次呢？")).getBody();
        assertThat(second.path("answer").asText()).contains("补单写入");
        assertThat(second.path("steps").findValuesAsText("action")).containsExactly("followLogReference","finish");
        verify(logs,times(1)).search(any(),any(),eq("GATEWAY"),any(),any(),any(),anyInt());
        doReturn(OrderSources.PosStatus.TIMEOUT).when(orders).queryPos("123457");
        var current=call(follow,"demo-ops-a-next",Map.of("question","现在 POS 有单吗？")).getBody();
        assertThat(current.path("answer").asText()).contains("TIMEOUT","无法确认");
        assertThat(current.path("steps").findValuesAsText("action")).containsExactly("queryPosOrder");
        assertThat(call(follow,"demo-ops-a",Map.of("question","请查订单223456")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void checkpointRestoresRemainingToolsAndAuthorizesNewOperator() {
        var path=endpoint("123457");
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx=inv.getArgument(0);
            if (ctx.exchanges().isEmpty()) return action("searchOrderLogs",window("GATEWAY"));
            assertThat(ctx.remainingTools()).isEqualTo(5);
            assertThat(ctx.evidence()).noneMatch(e -> e.kind().equals("SNAPSHOT"));
            return action("clarify",Map.of("question","请确认要查首次还是补单。"));
        });
        var first=call(path,"demo-ops-a",Map.of("question","查日志","stepLimit",1)).getBody();
        assertThat(first.path("status").asText()).isEqualTo("PAUSED");
        var resume=path+"/"+first.get("id").asText()+"/resume";
        db.update("UPDATE demo_operator_grant SET enabled=FALSE WHERE operator_name='duty-a'");
        try {
            assertThat(call(resume,"demo-ops-a",Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            db.update("UPDATE demo_operator_grant SET enabled=TRUE WHERE operator_name='duty-a'");
        }
        assertThat(call(resume,"demo-viewer",Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(resume,"demo-ops-b",Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var second=call(resume,"demo-ops-a-next",Map.of()).getBody();
        assertThat(second.path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(second.path("steps").findValuesAsText("operator")).containsExactly("duty-a","next-shift-a");
        assertThat(call(resume,"demo-ops-a",Map.of()).getBody()).isEqualTo(second);
    }

    @Test
    void concurrentResumeCommitsOnlyOnce() throws Exception {
        var path=endpoint("123457");
        var first=call(path,"demo-ops-a",Map.of("question","查首次","stepLimit",0)).getBody();
        String resume=path+"/"+first.path("id").asText()+"/resume";
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            entered.countDown();
            if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker timed out");
            return action("searchOrderLogs",window("GATEWAY"));
        });
        var worker=java.util.concurrent.CompletableFuture.supplyAsync(() -> call(resume,"demo-ops-a",Map.of("stepLimit",1)));
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(call(resume,"demo-ops-a-next",Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {release.countDown();}
        assertThat(worker.get().getBody().path("steps").size()).isEqualTo(1);
        verify(logs,times(1)).search(any(),any(),any(),any(),any(),any(),anyInt());
    }

    @Test
    void crashedReservationStaysChargedAndLegacyRunHasNoInventedCheckpoint() {
        var path=endpoint("123457");
        var run=call(path,"demo-ops-a",Map.of("question","查首次","stepLimit",0)).getBody();
        var id=UUID.fromString(run.get("id").asText());
        db.update("UPDATE investigation_checkpoint SET version=8,remaining_ms=0,lease_until=CURRENT_TIMESTAMP WHERE run_id=?",id);
        db.update("UPDATE investigation SET status='RUNNING' WHERE id=?",id);
        var result=call(path+"/"+id+"/resume","demo-ops-a-next",Map.of()).getBody();
        assertThat(result.path("status").asText()).isEqualTo("HANDOFF");
        verifyNoInteractions(model);
        db.update("DELETE FROM investigation_checkpoint WHERE run_id=?",id);
        assertThat(call(path+"/"+id+"/resume","demo-ops-a",Map.of()).getBody()).isEqualTo(result);
        db.update("UPDATE investigation SET status='RUNNING' WHERE id=?",id);
        assertThat(call(path+"/"+id+"/resume","demo-ops-a",Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsFabricatedCitationsAndWriteTools() {
        var path=endpoint("123457");
        when(model.choose(any(),anyList(),any())).thenReturn(action("repairOrder",Map.of()),
                action("finish",Map.of("answer","伪结论","citations",List.of(UUID.randomUUID()))));
        var result=run(path);
        assertThat(result.path("status").asText()).isEqualTo("HANDOFF");
        assertThat(result.path("steps").findValuesAsText("result")).containsExactly("INVALID_ACTION","INVALID_ACTION");
        assertThat(result.path("answer").asText()).doesNotContain("伪结论");
    }

    @Test
    void enforcesReadBudget() {
        var path=endpoint("123457");
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx=inv.getArgument(0);
            var args=window("GATEWAY");
            args.put("from","2026-11-18T02:0"+ctx.feedback().size()+":00Z");
            return action("searchOrderLogs",args);
        });
        var result=run(path);
        assertThat(result.path("steps").size()).isEqualTo(7);
        assertThat(result.at("/steps/6/result").asText()).isEqualTo("BUDGET_OR_REPEAT");
        verify(logs,times(6)).search(any(),any(),any(),any(),any(),any(),anyInt());
    }

    @Test
    void modelTimeoutPreservesEvidenceAndStatusQuery() {
        var path=endpoint("123457");
        verifyNoInteractions(model);
        when(model.choose(any(),anyList(),any())).thenThrow(new InvestigationModel.Unavailable("模型超时"));
        var result=run(path);
        assertThat(result.path("status").asText()).isEqualTo("HANDOFF");
        assertThat(result.path("citations").isEmpty()).isFalse();
        assertThat(call(path.replace("/investigations",""),"demo-ops-a",null).getBody().path("outcome").asText())
                .isEqualTo("POS_PRESENT");
    }
}
