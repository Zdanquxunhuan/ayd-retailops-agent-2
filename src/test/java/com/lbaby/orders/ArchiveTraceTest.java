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
}
