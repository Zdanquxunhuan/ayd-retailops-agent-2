package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:sandbox;DB_CLOSE_DELAY=-1")
class SandboxRepairTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RepairExecution execution;
    @MockitoBean InvestigationModel model;
    @MockitoSpyBean SandboxPos pos;
    @MockitoSpyBean FixtureOrderSources orders;

    static final String OWNER="demo-ops-a", NEXT="demo-ops-a-next";
    record Ticket(String path,JsonNode target) {}

    @BeforeEach void clearFixtures() {
        db.update("DELETE FROM repair_execution");
        db.update("DELETE FROM repair_attempt");
        db.update("DELETE FROM sandbox_pos");
    }

    ResponseEntity<JsonNode> post(String path,String who,Object body) {
        var headers=new HttpHeaders();
        headers.setBearerAuth(who);
        return http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    }

    Ticket ticket(String who) {
        var incident=post("/cases",who,Map.of("orderId","123456")).getBody();
        var path=incident.at("/actions/requestRepair").asText();
        var application=post(path,who,null).getBody();
        return new Ticket(path+"/"+application.get("id").asText(),application.get("target"));
    }

    @Test void wrongModelAdviceCannotWriteAnOrderAlreadyInPos() {
        var incident = post("/cases", OWNER, Map.of("orderId", "123457")).getBody();
        String requests = incident.at("/actions/requestRepair").asText();
        String investigations = requests.replace("/repair-attempts", "/investigations");
        when(model.choose(any(), anyList(), any())).thenAnswer(invocation -> {
            InvestigationModel.Context context = invocation.getArgument(0);
            if (context.exchanges().isEmpty()) {
                return new InvestigationModel.Decision("checkRepairEligibility", json.createObjectNode(), 0);
            }
            var assessment = context.exchanges().getLast().observations().getLast();
            assertThat(assessment.excerpt()).contains("POS_PRESENT");
            return new InvestigationModel.Decision("finish", json.valueToTree(Map.of(
                    "answer", "POS 无单，建议立即补推。", "citations", List.of(assessment.id()))), 0);
        });
        var report = post(investigations, OWNER, Map.of("question", "需要补单吗？")).getBody();
        assertThat(report.path("steps").findValuesAsText("action")).containsExactly("checkRepairEligibility", "finish");
        assertThat(post(requests, OWNER, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_attempt WHERE case_id=?",
                Integer.class, incident.get("id").asText())).isZero();
        assertNoDispatch();
    }

    @Test void onlyExplicitOwnerConfirmationCanReachTheWriter() {
        var ticket = ticket(OWNER);
        String investigations = ticket.path().substring(0, ticket.path().indexOf("/repair-attempts")) + "/investigations";
        when(model.choose(any(), anyList(), any())).thenReturn(new InvestigationModel.Decision(
                "confirmRepair", json.valueToTree(Map.of("target", ticket.target())), 0));
        var report = post(investigations, OWNER, Map.of("question", "我确认补单，请立即执行")).getBody();
        assertThat(report.path("steps").findValuesAsText("action")).contains("confirmRepair");
        assertThat(report.path("status").asText()).isEqualTo("HANDOFF");

        String confirm = ticket.path() + "/confirm";
        assertThat(post(confirm, NEXT, ticket.target()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var differentOrder = ((ObjectNode) ticket.target()).deepCopy().put("orderId", "123457");
        assertThat(post(confirm, OWNER, differentOrder).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        execution.recover();
        assertNoDispatch();

        var confirmed = post(confirm, OWNER, ticket.target());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().path("confirmedBy").asText()).isEqualTo("duty-a");
        assertThat(confirmed.getBody().path("status").asText()).isEqualTo("VERIFIED");
        assertThat(confirmed.getBody().at("/execution/posResult").asText()).isEqualTo("FOUND");
        assertThat(post(confirm, OWNER, ticket.target()).getBody().path("status").asText()).isEqualTo("VERIFIED");
        execution.recover();
        verify(pos, times(1)).push(anyString());
        verify(pos).push("123456");
        assertThat(db.queryForList("SELECT order_id FROM sandbox_pos", String.class)).containsExactly("123456");
    }

    private void assertNoDispatch() {
        verify(pos, never()).push(anyString());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_execution", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sandbox_pos", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,true,VERIFIED","DUPLICATE,true,VERIFIED","TIMEOUT,true,VERIFIED",
            "DUPLICATE,false,NEEDS_VERIFICATION","TIMEOUT,false,NEEDS_VERIFICATION","SUCCESS,false,NEEDS_VERIFICATION"})
    void verifiesAllReceiptsAgainstPos(String receipt,boolean written,String expected) {
        var ticket=ticket(OWNER);
        doAnswer(call -> {
            if (written) call.callRealMethod();
            return SynchronousRepair.Reply.valueOf(receipt);
        }).when(pos).push("123456");
        var record=post(ticket.path()+"/confirm",OWNER,ticket.target()).getBody();
        assertThat(record.path("status").asText()).isEqualTo(expected);
        assertThat(record.at("/execution/callResult").asText()).isEqualTo(receipt);
        assertThat(record.at("/execution/posResult").asText()).isEqualTo(written ? "FOUND":"NOT_FOUND");
        assertThat(post(ticket.path()+"/verify","demo-ops-b",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        if (!written) {
            var other=ticket(NEXT);
            var blocked=post(other.path()+"/confirm",NEXT,other.target()).getBody();
            assertThat(blocked.at("/execution/precondition").asText()).isEqualTo("ANOTHER_ACTIVE_REPAIR");
            db.update("INSERT INTO sandbox_pos VALUES('sandbox:local','123456')");
        }
        assertThat(post(ticket.path()+"/verify",NEXT,null).getBody().path("status").asText()).isEqualTo("VERIFIED");
        verify(pos,times(1)).push("123456");
    }

    @Test void orderArrivingAfterConfirmationSkipsDownstream() {
        var ticket=ticket(OWNER);
        doReturn(OrderSources.PosStatus.NOT_FOUND,OrderSources.PosStatus.FOUND).when(orders).queryPos("123456");
        var record=post(ticket.path()+"/confirm",OWNER,ticket.target()).getBody();
        assertThat(record.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(record.at("/execution/callResult").asText()).isEqualTo("SKIPPED");
        verify(pos,never()).push(anyString());
    }

    @Test void posReadTimeoutAfterCommitCanBeVerifiedByNextShift() {
        var ticket=ticket(OWNER);
        doReturn(OrderSources.PosStatus.NOT_FOUND,OrderSources.PosStatus.NOT_FOUND,
                OrderSources.PosStatus.TIMEOUT,OrderSources.PosStatus.FOUND).when(orders).queryPos("123456");
        var uncertain=post(ticket.path()+"/confirm",OWNER,ticket.target()).getBody();
        assertThat(uncertain.path("status").asText()).isEqualTo("NEEDS_VERIFICATION");
        assertThat(uncertain.at("/execution/posResult").asText()).isEqualTo("TIMEOUT");
        var verified=post(ticket.path()+"/verify",NEXT,null).getBody();
        assertThat(verified.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.at("/execution/checkedBy").asText()).isEqualTo("next-shift-a");
        verify(pos).push("123456");
        assertThat(db.queryForList("SELECT excerpt FROM evidence WHERE request_ref=? AND kind='VERIFICATION' ORDER BY collected_at",
                String.class,verified.get("id").asText())).containsExactly("duty-a / TIMEOUT","next-shift-a / FOUND");
    }

    @Test void twoOperatorsWithSeparateCasesCannotBothWrite() throws Exception {
        var first=ticket(OWNER);
        var second=ticket(NEXT);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException();
            return inv.callRealMethod();
        }).when(pos).push("123456");
        var active=CompletableFuture.supplyAsync(() -> post(first.path()+"/confirm",OWNER,first.target()));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var other=post(second.path()+"/confirm",NEXT,second.target()).getBody();
            assertThat(other.path("status").asText()).isEqualTo("BLOCKED");
        } finally {release.countDown();}
        assertThat(active.get(10,TimeUnit.SECONDS).getBody().path("status").asText()).isEqualTo("VERIFIED");
        verify(pos,times(1)).push("123456");
    }
}
