package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.datasource.url=jdbc:h2:mem:repairs;DB_CLOSE_DELAY=-1","repair.fixture-environment=tests","repair.sandbox-enabled=false"})
class RepairHttpTest {
    static final String OWNER = "demo-ops-a", COLLEAGUE = "demo-ops-a-next", FOREIGN = "demo-ops-b";
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoSpyBean FixtureOrderSources orders;
    @MockitoBean InvestigationModel model;

    ResponseEntity<JsonNode> call(String path, String actor, HttpMethod method, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(actor);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }

    String endpoint(String order) {
        var reply = call("/cases",OWNER,HttpMethod.POST,Map.of("orderId",order));
        return reply.getBody().at("/actions/requestRepair").asText();
    }

    JsonNode apply(String path) {
        var reply = call(path,OWNER,HttpMethod.POST,null);
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reply.getBody().path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");
        return reply.getBody();
    }

    ResponseEntity<JsonNode> confirm(String path, JsonNode attempt, String actor, Object target) {
        return call(path + "/" + attempt.get("id").asText() + "/confirm",actor,HttpMethod.POST,target);
    }

    @Test
    void confirmsOnceWithFreshEvidenceEvenWhenRequestsOverlap() {
        var path = endpoint("123456");
        var first = apply(path);
        assertThat(apply(path)).isEqualTo(first);
        assertThat(first.get("environment").asText()).isEqualTo("fixture:tests");
        assertThat(first.path("applicationEvidence").findValuesAsText("excerpt")).containsExactly("NOT_FOUND","COMPLETED");
        clearInvocations(orders);
        var concurrent = CompletableFuture.supplyAsync(() -> confirm(path,first,OWNER,first.get("target")));
        var result = confirm(path,first,OWNER,first.get("target"));
        var repeat = concurrent.join();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        var record = result.getBody();
        assertThat(repeat.getBody()).isEqualTo(record);
        assertThat(record.path("confirmedBy").asText()).isEqualTo("duty-a");
        assertThat(record.path("status").asText()).isEqualTo("PENDING_EXECUTION");
        assertThat(record.path("confirmationEvidence").findValuesAsText("id"))
                .hasSize(2).doesNotContainAnyElementsOf(first.path("applicationEvidence").findValuesAsText("id"));
        assertThat(call(path,COLLEAGUE,HttpMethod.GET,null).getBody().get(0)).isEqualTo(record);
        verify(orders).storeOf("123456");
        verify(orders).queryPos("123456");
        verify(orders).queryMeituan("123456");
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @CsvSource({"123457,POS_PRESENT","123458,POS_TIMEOUT","123459,POS_DENIED","123460,MEITUAN_NOT_COMPLETED",
            "123461,MEITUAN_NOT_FOUND","123462,MEITUAN_TIMEOUT","123463,MEITUAN_DENIED"})
    void currentIneligibleOrdersCannotCreateAnApplication(String order, String reason) {
        var path = endpoint(order);
        var preview = call(path.replace("/repair-attempts","/repair-eligibility"),OWNER,HttpMethod.GET,null);
        assertThat(preview.getBody().path("eligible").asBoolean()).isFalse();
        var reply = call(path,OWNER,HttpMethod.POST,null);
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reply.getBody().path("reason").asText()).isEqualTo(reason);
        assertThat(call(path,OWNER,HttpMethod.GET,null).getBody()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"LATE_DELIVERY,POS_PRESENT","POS_OFFLINE,POS_TIMEOUT","TRADE_CHANGED,MEITUAN_NOT_COMPLETED",
            "MOVED,ORDER_SCOPE_CHANGED","EXPIRED,EXPIRED"})
    void invalidationSurvivesReadAndRetry(String event, String reason) {
        var path = endpoint("123456");
        var attempt = apply(path);
        var id = attempt.get("id").asText();
        switch (event) {
            case "LATE_DELIVERY" -> doReturn(OrderSources.PosStatus.FOUND).when(orders).queryPos("123456");
            case "POS_OFFLINE" -> doReturn(OrderSources.PosStatus.TIMEOUT).when(orders).queryPos("123456");
            case "TRADE_CHANGED" -> doReturn(OrderSources.MeituanStatus.NOT_COMPLETED).when(orders).queryMeituan("123456");
            case "MOVED" -> doReturn(Optional.of("033102")).when(orders).storeOf("123456");
            case "EXPIRED" -> db.update("UPDATE repair_attempt SET expires_at=created_at WHERE id=?",id);
        }
        var reply = confirm(path,attempt,OWNER,attempt.get("target"));
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var invalid = reply.getBody();
        assertThat(invalid.path("reason").asText()).isEqualTo(reason);
        assertThat(invalid.path("confirmedAt").isNull()).isTrue();
        assertThat(call(path + "/" + id,OWNER,HttpMethod.GET,null).getBody()).isEqualTo(invalid);
        assertThat(confirm(path,attempt,OWNER,attempt.get("target")).getBody()).isEqualTo(invalid);
    }

    @Test
    void confirmationIsBoundToRequesterCaseEnvironmentAndReviewedTarget() {
        var path = endpoint("123456");
        var attempt = apply(path);
        var target = attempt.get("target");
        assertThat(confirm(path,attempt,COLLEAGUE,target).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirm(path,attempt,FOREIGN,target).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(path,FOREIGN,HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirm(endpoint("123457"),attempt,OWNER,target).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String key : List.of("orderId","action","channel")) {
            var changed = ((com.fasterxml.jackson.databind.node.ObjectNode)target).deepCopy();
            changed.put(key,"changed");
            assertThat(confirm(path,attempt,OWNER,changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
        db.update("UPDATE repair_attempt SET environment='fixture:elsewhere' WHERE id=?",attempt.get("id").asText());
        assertThat(call(path,OWNER,HttpMethod.GET,null).getBody()).isEmpty();
        assertThat(confirm(path,attempt,OWNER,target).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revokingOnlyRepairPermissionInvalidatesTheConfirmation() {
        var path = endpoint("123456");
        var attempt = apply(path);
        db.update("UPDATE demo_operator_grant SET can_request_repair=FALSE WHERE operator_name='duty-a'");
        try {
            assertThat(call(path,OWNER,HttpMethod.POST,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(confirm(path,attempt,OWNER,attempt.get("target")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(path,OWNER,HttpMethod.GET,null).getBody().get(0).path("reason").asText())
                    .isEqualTo("PERMISSION_REVOKED");
        } finally {
            db.update("UPDATE demo_operator_grant SET can_request_repair=TRUE WHERE operator_name='duty-a'");
        }
        assertThat(confirm(path,attempt,OWNER,attempt.get("target")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resumedInvestigationRefreshesEligibilityAfterLateDelivery() {
        var path = endpoint("123456");
        var runs = path.replace("repair-attempts","investigations");
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx = inv.getArgument(0);
            if (ctx.exchanges().isEmpty()) {
                assertThat(ctx.evidence()).noneMatch(e -> e.kind().equals("ASSESSMENT"));
                return new InvestigationModel.Decision("checkRepairEligibility",json.createObjectNode(),0);
            }
            var latest = ctx.exchanges().getLast().observations().getLast();
            return new InvestigationModel.Decision("finish",json.valueToTree(Map.of("answer",latest.excerpt(),
                    "citations",List.of(latest.id()))),0);
        });
        var paused = call(runs,OWNER,HttpMethod.POST,Map.of("question","可以申请补单吗？","stepLimit",1)).getBody();
        assertThat(paused.path("status").asText()).isEqualTo("PAUSED");
        doReturn(OrderSources.PosStatus.FOUND).when(orders).queryPos("123456");
        var report = call(runs + "/" + paused.path("id").asText() + "/resume",COLLEAGUE,HttpMethod.POST,null).getBody();
        assertThat(report.path("answer").asText()).contains("POS_PRESENT").doesNotContain(path);
        assertThat(report.path("steps").findValuesAsText("action"))
                .containsExactly("checkRepairEligibility","checkRepairEligibility","finish");
        assertThat(call(path,OWNER,HttpMethod.GET,null).getBody()).isEmpty();
    }

    @Test
    void agentCanExplainAndLinkButChatCannotCreateOrConfirm() {
        var path = endpoint("123456");
        when(model.choose(any(),anyList(),any())).thenAnswer(inv -> {
            InvestigationModel.Context ctx = inv.getArgument(0);
            if (ctx.exchanges().isEmpty()) return new InvestigationModel.Decision("checkRepairEligibility",json.createObjectNode(),0);
            var rule = ctx.exchanges().getLast().observations().getLast();
            return new InvestigationModel.Decision("finish",json.valueToTree(Map.of("answer",rule.excerpt(),
                    "citations",List.of(rule.id()))),0);
        });
        var runs = path.replace("repair-attempts","investigations");
        var report = call(runs,OWNER,HttpMethod.POST,Map.of("question","这单现在能补吗？")).getBody();
        assertThat(report.path("steps").findValuesAsText("action")).containsExactly("checkRepairEligibility","finish");
        assertThat(report.path("answer").asText()).contains(path);
        assertThat(call(path,OWNER,HttpMethod.GET,null).getBody()).isEmpty();
        var first = apply(path);
        doReturn(new InvestigationModel.Decision("confirmRepair",json.valueToTree(Map.of("id",first.get("id"))),0))
                .when(model).choose(any(),anyList(),any());
        var reply = call(runs + "/" + report.get("id").asText() + "/follow-ups",OWNER,HttpMethod.POST,
                Map.of("question","确认补单")).getBody();
        assertThat(reply.path("status").asText()).isEqualTo("HANDOFF");
        assertThat(call(path,OWNER,HttpMethod.GET,null).getBody().get(0)).isEqualTo(first);
    }
}
