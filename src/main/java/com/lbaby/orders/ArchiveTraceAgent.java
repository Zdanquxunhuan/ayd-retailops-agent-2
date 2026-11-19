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
    enum FailureQuery { LATEST_FAILURE, COMPARE_FAILURES }
    private record Signature(String type,String message,String method) {}
    private static Map<String,Object> tool(String name,String description) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,
                "parameters",Map.of("type","object","properties",Map.of(),"additionalProperties",false)));
    }
    private static final List<Map<String,Object>> TOOLS=List.of(tool("firstAttempt","检查文件里最早的处理"),
            tool("latestAttempt","检查文件里最近的处理"),tool("compareAttempts","逐次对比归档内的回传"),
            tool("latestFailure","定位最近一次明确报告失败的请求及异常来源"),
            tool("compareFailures","比较各次明确失败所报告的异常，找相同和不同的失败"),
            tool("clarify","说明本工具能查询的范围"));
    private final PushArchiveLookup archives;
    private final QwenModel model;
    ArchiveTraceAgent(PushArchiveLookup archives,QwenModel model) { this.archives=archives; this.model=model; }

    Report trace(String order,LocalDate date,String question) {
        return trace(order,date,question,null);
    }

    Report trace(String order,LocalDate date,String question,FailureQuery query) {
        String planner="LOCAL", action=switch(query) {
            case null -> "compareAttempts";
            case LATEST_FAILURE -> "latestFailure";
            case COMPARE_FAILURES -> "compareFailures";
        };
        if (question!=null && !question.isBlank()) {
            try {
                // Only the operator's question reaches the planner. Never send archive evidence or the bound order.
                var decision=model.selectArchiveTool(question.replaceAll("[0-9]+","[编号]"),TOOLS);
                action=decision.action();
                planner="QWEN_FLASH";
                if (!decision.args().isObject() || !decision.args().isEmpty() ||
                        !Set.of("firstAttempt","latestAttempt","compareAttempts","latestFailure","compareFailures").contains(action))
                    return new Report(planner,"clarify",null,0,List.of(),"可以查询首次、最近一次回传，或追踪和比较已报告的失败。");
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
        if (action.equals("latestFailure") || action.equals("compareFailures"))
            attempts=attempts.stream().filter(a->a.serviceOutcome().equals("REPORTED_FAILURE")).toList();
        if (!attempts.isEmpty() && Set.of("firstAttempt","latestAttempt","latestFailure").contains(action)) {
            String time=(action.equals("firstAttempt") ? attempts.getFirst() : attempts.getLast()).time();
            attempts=attempts.stream().filter(attempt -> time.equals(attempt.time())).toList();
        }
        var answer=new StringBuilder();
        if (coverage.status()!=PushArchiveLookup.Status.SCANNED)
            answer.append("归档覆盖不足（").append(coverage.status()).append("）。");
        answer.append(total==0 ? "在已读取范围内未定位到该订单的控制器入口。" : "已定位 "+total+" 次控制器入口。");
        if (total>0 && attempts.isEmpty()) answer.append("已读范围内无可归属的明确失败结果。");
        if (action.equals("compareFailures") || action.equals("compareAttempts")) answer.append(compareFailures(attempts));
        for (var attempt:attempts) {
            answer.append(" ").append(attempt.file()).append(" 第 ").append(attempt.controllerLine()).append(" 行对应的处理：");
            answer.append(switch(attempt.serviceOutcome()) {
                case "REPORTED_SUCCESS" -> "服务日志报告推送成功。";
                case "REPORTED_FAILURE" -> failureDetails(attempt);
                case "REPORTED_DUPLICATE" -> "服务日志报告订单已存在。";
                default -> "缺少可归属的完整处理结果，写入结果待核实。";
            });
        }
        if (attempts.stream().map(PushAttemptTrace.Attempt::failure).filter(Objects::nonNull)
                .anyMatch(e -> e.safeMessage()!=null))
            answer.append("这些记录给出了程序抛出的消息，具体业务比较条件仍需核对抛错处的代码。");
        return new Report(planner,action,coverage,total,attempts,answer.toString());
    }

    private String failureDetails(PushAttemptTrace.Attempt attempt) {
        String result="第 "+attempt.resultLine()+" 行报告推送失败。";
        var e=attempt.failure();
        if (e==null) return result+"未取得可归属的异常。";
        return result+"第 "+e.exceptionLine()+" 行记录 "+e.exceptionType()
                +(e.safeMessage()==null ? "，消息未识别或已隐藏。" : "：“"+e.safeMessage()+"”。")
                +(e.businessMethod()==null ? "业务栈帧未定位。" : "第 "+e.frameLine()+" 行："+e.businessMethod()+"。");
    }

    private String compareFailures(List<PushAttemptTrace.Attempt> attempts) {
        var groups=new HashSet<Signature>();
        int failed=0, complete=0;
        for (var a:attempts) {
            if (!a.serviceOutcome().equals("REPORTED_FAILURE")) continue;
            failed++;
            var e=a.failure();
            if (e!=null && e.safeMessage()!=null && e.businessMethod()!=null) {
                groups.add(new Signature(e.exceptionType(),e.safeMessage(),e.businessMethod()));
                complete++;
            }
        }
        if (failed<2) return "";
        if (groups.size()==1 && complete==failed)
            return "已读范围内 "+complete+"/"+failed+" 次失败报告同一异常（类型、消息和业务方法一致）。";
        return "已读范围内 "+complete+"/"+failed+" 次失败可比较，已识别 "+groups.size()+" 种异常组合。"
                +(complete<failed ? "其余失败证据不足。":"");
    }
}
