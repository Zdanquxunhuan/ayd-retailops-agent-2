package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "POS_SHADOW_TOKEN=case-test","POS_DB_PASSWORD=unused","spring.datasource.url=jdbc:h2:mem:shadow_work",
        "PUSH_LOG_ARCHIVE=target/shadow-work-test","SHADOW_MT_ORDER_ID=123456"})
@ActiveProfiles("pos-shadow")
class ShadowCasesHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoBean PosShadowLookup pos;
    @MockitoBean MeituanShadowLookup mt;
    @MockitoBean QwenModel model;
    static final String DAY="2026-11-11";
    static final String BASE="/pos-shadow/cases";
    static String line(int sec,String logger,String text) {
        return DAY+" 18:00:%02d.001 INFO 123 --- [worker] a.b.%s : %s\n".formatted(sec,logger,text);
    }
    static String attempt(int s,String outcome) {
        String route=" request [POST /meituan/pushFinishedOrder,";
        return line(s,"RequestLoggingFilter","Before"+route)+line(s+1,"MeituanController","准备推送的订单号为 order_id:123456")
                +line(s+2,"MeituanServiceImpl",outcome)+line(s+3,"RequestLoggingFilter","After"+route);
    }
    @BeforeEach void fixtures() throws Exception {
        var folder=Files.createDirectories(Path.of("target/shadow-work-test"));
        try (var out=new GZIPOutputStream(Files.newOutputStream(folder.resolve("b-push-center-server.log."+DAY+".2.gz")))) {
            out.write((attempt(0,"订单123456信息推送成功！")+attempt(4,"123456订单已存在")).getBytes(UTF_8));
        }
        when(model.selectCaseTool(anyString(),anyString(),anyList()))
                .thenThrow(new InvestigationModel.Unavailable("offline"));
    }
    ResponseEntity<JsonNode> call(String path,Object body,String token) {
        var h=new HttpHeaders(); h.setBearerAuth(token);
        return http.exchange(path,body==null ? HttpMethod.GET : HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);
    }
    JsonNode post(String path,Object body) {
        var res=call(path,body,"case-test");
        assertThat(res.getStatusCode().is2xxSuccessful()).as(res.getBody().toString()).isTrue();
        return res.getBody();
    }
    PosShadowLookup.Observation observed(String id,PosShadowLookup.Status status) {
        return new PosShadowLookup.Observation(id,status,status==PosShadowLookup.Status.FOUND || status==PosShadowLookup.Status.PARTIAL,
                status==PosShadowLookup.Status.FOUND,Instant.now());
    }
    @Test void presentOrderStillTracesFirstAndFollowUpUsesSameSavedArchive() {
        when(pos.query("123456")).thenReturn(observed("123456",PosShadowLookup.Status.FOUND));
        var first=post(BASE,Map.of("orderId","123456","date",DAY,"question","这单现在有了，但第一次回传如何？"));
        String path=BASE+"/"+first.get("id").asText();
        assertThat(first.at("/turns/0/pos/status").asText()).isEqualTo("FOUND");
        assertThat(first.at("/turns/0/history/attempts/0/serviceOutcome").asText()).isEqualTo("REPORTED_SUCCESS");
        doReturn(new InvestigationModel.Decision("LATEST",JsonNodeFactory.instance.objectNode(),5))
                .when(model).selectCaseTool(eq("那最后一次呢？"),eq("FIRST"),anyList());
        var next=post(path+"/turns",Map.of("question","那最后一次呢？"));
        var turn=next.at("/turns/1");
        assertThat(turn.path("steps").toString()).contains("reuseArchive").doesNotContain("scanArchive","queryPosOrder");
        assertThat(turn.path("archiveReadTurn").asInt()).isEqualTo(1);
        assertThat(turn.at("/history/attempts/0/serviceOutcome").asText()).isEqualTo("REPORTED_DUPLICATE");
        assertThat(call(path,null,"case-test").getBody()).isEqualTo(next);
        post(path+"/turns",Map.of("intent","CURRENT"));
        verify(pos,times(2)).query("123456"); verifyNoInteractions(mt);
        assertThat(call(path,null,"foreign").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void reports838AsUnknownAndRequestsMissingArchiveDate() {
        when(pos.query("123456")).thenReturn(observed("123456",PosShadowLookup.Status.NOT_FOUND));
        when(mt.query("123456")).thenReturn(new MeituanShadowLookup.Fact(MeituanShadowLookup.State.OUTSIDE_QUERY_WINDOW,
                null,null,null,List.of(838),"replay-id",Instant.now()));
        var view=post(BASE,Map.of("orderId","123456"));
        assertThat(view.at("/turns/0/gaps").toString()).contains("838","状态未知","日期");
        var next=post(BASE+"/"+view.get("id").asText()+"/turns",Map.of("date","2026-10-09"));
        assertThat(next.at("/turns/1/gaps").toString()).contains("2026-10-09","NO_ARCHIVES");
        verify(pos).query("123456"); verify(mt).query("123456");
    }
    @Test void partialOrUnapprovedOrderCannotTriggerOnlineDetail() {
        when(pos.query("333333")).thenReturn(observed("333333",PosShadowLookup.Status.PARTIAL),
                observed("333333",PosShadowLookup.Status.NOT_FOUND));
        var partial=post(BASE,Map.of("orderId","333333"));
        assertThat(partial.at("/turns/0/steps").size()).isEqualTo(1);
        var missing=post(BASE+"/"+partial.get("id").asText()+"/turns",Map.of("intent","CURRENT"));
        assertThat(missing.at("/turns/1/gaps").toString()).contains("授权");
        verifyNoInteractions(mt);
    }
    @Test void comparesFourFailuresAndReadsEachExceptionLine() throws Exception {
        var folder=Path.of("target/shadow-work-test");
        String stack="订单123456信息推送失败！\n\njava.lang.RuntimeException: 收益流水和销售流水不同\n"
                +"\tat cn.com.huoban168.b.push.center.service.pos.impl.MeituanServiceImpl.dealFinishedOrder(MeituanServiceImpl.java:1)";
        try(var out=new GZIPOutputStream(Files.newOutputStream(folder.resolve("b-push-center-server.log."+DAY+".2.gz")))) {
            out.write((attempt(0,stack)+attempt(6,stack)+attempt(12,stack)+attempt(18,stack)).getBytes(UTF_8));
        }
        when(pos.query("123456")).thenReturn(observed("123456",PosShadowLookup.Status.FOUND));
        var view=post(BASE,Map.of("orderId","123456","date",DAY,"intent","COMPARE_FAILURES"));
        assertThat(view.at("/turns/0/answer").asText()).contains("4/4","收益流水和销售流水不同");
        var attempts=view.at("/turns/0/history/attempts");
        assertThat(attempts).hasSize(4);
        assertThat(attempts.findValuesAsText("exceptionLine")).containsExactly("5","12","19","26");
        verifyNoInteractions(mt);
    }
}
