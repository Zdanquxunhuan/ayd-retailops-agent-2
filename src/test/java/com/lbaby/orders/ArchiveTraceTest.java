package com.lbaby.orders;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArchiveTraceTest {
    @TempDir Path dir;
    static final String ORDER="123456";
    static final String ROUTE="request [POST /meituan/pushFinishedOrder,";
    String log(int sec,String worker,String logger,String msg) {
        return "2026-11-11 18:00:%02d.001 INFO 123 --- [%s] a.b.%s : %s\n".formatted(sec,worker,logger,msg);
    }
    String boundary(int sec,String worker,String kind) {
        return log(sec,worker,"RequestLoggingFilter",kind+" "+ROUTE);
    }
    String entry(int sec,String worker,String order) {
        return log(sec,worker,"MeituanController","准备推送的订单号为 order_id:"+order);
    }
    String result(int sec,String worker,String msg) { return log(sec,worker,"MeituanServiceImpl",msg); }
    void archive(int part,String data) throws Exception {
        try(var out=new GZIPOutputStream(Files.newOutputStream(dir.resolve("b-push-center-server.log.2026-11-11."+part+".gz")))) {
            out.write(data.getBytes(UTF_8));
        }
    }
    @Test void separatesConcurrentOrdersAndRepeatedAttemptsAndChecksModelChoice() throws Exception {
        archive(1,boundary(1,"w1","Before")+entry(2,"w1",ORDER)+boundary(3,"w2","Before")+entry(4,"w2","999")
                +result(5,"w2","订单123456信息推送成功！")+result(6,"w1","订单123456信息推送失败！")
                +boundary(7,"w1","After")+boundary(8,"w2","After")+boundary(9,"w1","Before")
                +entry(10,"w1",ORDER)+result(11,"w1","123456订单已存在")+boundary(12,"w1","After"));
        var model=mock(QwenModel.class);
        when(model.selectArchiveTool(eq("最近一次[编号]"),anyList())).thenReturn(new InvestigationModel.Decision(
                "latestAttempt",JsonNodeFactory.instance.objectNode(),10));
        var agent=new ArchiveTraceAgent(new PushArchiveLookup(dir.toString()),model);
        var day=LocalDate.parse("2026-11-11");
        var all=agent.trace(ORDER,day,null);
        assertThat(all.attempts()).extracting(PushAttemptTrace.Attempt::serviceOutcome)
                .containsExactly("REPORTED_FAILURE","REPORTED_DUPLICATE");
        assertThat(all.attempts().getFirst().resultLine()).isEqualTo(6L);
        verifyNoInteractions(model);
        var last=agent.trace(ORDER,day,"最近一次123456");
        assertThat(last.observedAttempts()).isEqualTo(2);
        assertThat(last.attempts()).containsExactly(all.attempts().getLast());
    }

    @Test void neverBorrowsResultsFromAnotherRequestOrAnotherArchive() throws Exception {
        archive(1,entry(1,"w",ORDER)+boundary(2,"w","Before")+entry(3,"w",ORDER)+
                result(4,"w","订单123456信息推送成功！")+boundary(5,"w","Before")+entry(6,"w",ORDER)+
                result(7,"w","订单123456信息推送成功！"));
        archive(2,boundary(8,"w","After")+boundary(9,"w","Before")+entry(10,"w",ORDER)+
                result(11,"w","订单999信息推送成功！")+boundary(12,"w","After"));
        var model=mock(QwenModel.class);
        when(model.selectArchiveTool(anyString(),anyList())).thenThrow(new InvestigationModel.Unavailable("offline"));
        var report=new ArchiveTraceAgent(new PushArchiveLookup(dir.toString()),model)
                .trace(ORDER,LocalDate.parse("2026-11-11"),"追踪");
        assertThat(report.planner()).isEqualTo("MODEL_UNAVAILABLE_LOCAL_FALLBACK");
        assertThat(report.attempts()).hasSize(4).allMatch(a->a.serviceOutcome().equals("UNKNOWN") && a.resultLine()==null);
    }

    static final String MESSAGE="收益流水和销售流水不同";
    static final String FRAME="\tat cn.com.huoban168.b.push.center.service.pos.impl.MeituanServiceImpl.dealFinishedOrder(MeituanServiceImpl.java:123)\n";
    String failure(int sec,String tail) {
        return boundary(sec,"w","Before")+entry(sec+1,"w",ORDER)+result(sec+2,"w","订单123456信息推送失败！")
                +tail+boundary(sec+3,"w","After");
    }
    @Test void comparesLoggedExceptionsAndSelectsOnlyLatestFailedAttempt() throws Exception {
        String stack="\njava.lang.RuntimeException: "+MESSAGE+"\n"+FRAME;
        archive(1,failure(1,stack)+failure(8,stack)+boundary(16,"w","Before")+entry(17,"w",ORDER)
                +result(18,"w","订单123456信息推送成功！")+boundary(19,"w","After"));
        var model=mock(QwenModel.class);
        var day=LocalDate.parse("2026-11-11");
        var agent=new ArchiveTraceAgent(new PushArchiveLookup(dir.toString()),model);
        var comparison=agent.trace(ORDER,day,null,ArchiveTraceAgent.FailureQuery.COMPARE_FAILURES);
        assertThat(comparison.observedAttempts()).isEqualTo(3);
        assertThat(comparison.attempts()).hasSize(2);
        assertThat(comparison.answer()).contains("2/2 次失败报告同一异常",MESSAGE);
        assertThat(comparison.attempts().getFirst().failure().frameLine()).isEqualTo(6L);
        when(model.selectArchiveTool(anyString(),anyList())).thenReturn(new InvestigationModel.Decision(
                "latestFailure",JsonNodeFactory.instance.objectNode(),10));
        var last=agent.trace(ORDER,day,"最后一次失败的原因呢？");
        assertThat(last.attempts()).containsExactly(comparison.attempts().getLast());
        assertThat(last.attempts().getFirst().failure().exceptionLine()).isEqualTo(12L);
    }
    @Test void neverComparesHiddenMessagesOrBorrowsFramesAcrossEvents() throws Exception {
        String nextEvent=log(5,"other","OtherLogger","other event");
        String stack="java.lang.RuntimeException: "+MESSAGE+"\n"+FRAME;
        archive(1,failure(1,nextEvent+stack)+failure(8,"java.lang.RuntimeException: "+MESSAGE+"\n"+nextEvent+FRAME)
                +failure(16,"java.lang.RuntimeException: sensitive-order-12345\n"+FRAME)
                +boundary(24,"w","Before")+entry(25,"w",ORDER)+result(26,"w","订单123456信息推送失败！"));
        archive(2,stack+boundary(27,"w","After"));
        var report=new ArchiveTraceAgent(new PushArchiveLookup(dir.toString()),mock(QwenModel.class))
                .trace(ORDER,LocalDate.parse("2026-11-11"),null);
        assertThat(report.attempts().get(0).failure()).isNull();
        assertThat(report.attempts().get(1).failure().businessMethod()).isNull();
        assertThat(report.attempts().get(2).failure().safeMessage()).isNull();
        assertThat(report.attempts().get(3).failure()).isNull();
        assertThat(report.toString()).contains("0/3 次失败可比较").doesNotContain("sensitive-order","报告同一异常");
    }

    @Test void equalMessagesAtDifferentBusinessMethodsAreDifferentEvidence() throws Exception {
        String stack="java.lang.RuntimeException: "+MESSAGE+"\n"+FRAME;
        archive(1,failure(1,stack)+failure(8,stack.replace("dealFinishedOrder","pushFinishedOrder")));
        var report=new ArchiveTraceAgent(new PushArchiveLookup(dir.toString()),mock(QwenModel.class))
                .trace(ORDER,LocalDate.parse("2026-11-11"),null,ArchiveTraceAgent.FailureQuery.COMPARE_FAILURES);
        assertThat(report.answer()).contains("2/2 次失败可比较","2 种异常组合").doesNotContain("报告同一异常");
    }
}
