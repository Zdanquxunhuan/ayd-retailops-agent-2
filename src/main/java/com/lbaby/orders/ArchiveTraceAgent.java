package com.lbaby.orders;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

@Service
@Profile("pos-shadow")
class ArchiveTraceAgent {
    record Report(String planner,String tool,PushArchiveLookup.Coverage coverage,int observedAttempts,
                  List<PushAttemptTrace.Attempt> attempts,String answer) {}
    private static Map<String,Object> tool(String name,String description) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,
                "parameters",Map.of("type","object","properties",Map.of(),"additionalProperties",false)));
    }
    private static final List<Map<String,Object>> TOOLS=List.of(tool("firstAttempt","检查文件里最早的处理"),
            tool("latestAttempt","检查文件里最近的处理"),tool("compareAttempts","逐次对比归档内的回传"),
            tool("clarify","说明本工具能查询的范围"));
    private final PushArchiveLookup archives;
    private final QwenModel model;
    ArchiveTraceAgent(PushArchiveLookup archives,QwenModel model) { this.archives=archives; this.model=model; }

    Report trace(String order,LocalDate date,String question) {
        String planner="LOCAL", action="compareAttempts";
        if (question!=null && !question.isBlank()) {
            try {
                // Only the operator's question reaches the planner. Never send archive evidence or the bound order.
                var decision=model.selectArchiveTool(question.replaceAll("[0-9]+","[编号]"),TOOLS);
                action=decision.action();
                planner="QWEN_FLASH";
                if (!decision.args().isObject() || !decision.args().isEmpty() ||
                        !Set.of("firstAttempt","latestAttempt","compareAttempts").contains(action))
                    return new Report(planner,"clarify",null,0,List.of(),"请选择查询最早一次、最近一次或比较多次回传。");
            } catch (InvestigationModel.Unavailable offline) {
                planner="MODEL_UNAVAILABLE_LOCAL_FALLBACK";
            }
        }
        var tracker=new PushAttemptTrace(order);
        var coverage=archives.scan(date,tracker);
        List<PushAttemptTrace.Attempt> attempts;
        try { attempts=tracker.finish(); }
        catch (PushArchiveLookup.Limit truncated) {
            coverage=new PushArchiveLookup.Coverage(date,coverage.observedAt(),PushArchiveLookup.Status.TRUNCATED,
                    coverage.files(),coverage.filesCompleted());
            attempts=tracker.sorted();
        }
        int total=attempts.size();
        if (total>0 && !action.equals("compareAttempts")) {
            String time=(action.equals("firstAttempt") ? attempts.getFirst() : attempts.getLast()).time();
            attempts=attempts.stream().filter(attempt -> time.equals(attempt.time())).toList();
        }
        String answer=total==0 ? "在已读取范围内未定位到该订单的控制器入口。" :
                "已定位 "+total+" 次控制器入口。";
        for (var attempt:attempts) {
            answer+=" "+attempt.file()+" 第 "+attempt.controllerLine()+" 行对应的处理："+switch(attempt.serviceOutcome()) {
                case "REPORTED_SUCCESS" -> "服务日志报告推送成功。";
                case "REPORTED_FAILURE" -> "服务日志报告推送失败，具体失败原因待核实。";
                case "REPORTED_DUPLICATE" -> "服务日志报告订单已存在。";
                default -> "缺少可归属的完整处理结果，写入结果待核实。";
            };
        }
        if (coverage.status()!=PushArchiveLookup.Status.SCANNED)
            answer="归档覆盖不足（"+coverage.status()+"）。"+answer;
        return new Report(planner,action,coverage,total,attempts,answer);
    }
}
