package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.lbaby.orders.InvestigationTools.fields;
import static com.lbaby.orders.InvestigationTools.string;

@Service
class AgentRunner {
    static final int MAX_TOOLS = 6, MAX_TURNS = 8;
    private final IncidentCases cases;
    private final InvestigationHistory history;
    private final InvestigationModel model;
    private final InvestigationTools tools;

    private final TransactionTemplate transaction;
    private static final java.util.regex.Pattern ORDER_IN_TEXT =
            java.util.regex.Pattern.compile("订单(?:号)?[\\s：:#为是]*(\\d{6,64})(?!\\d)");

    AgentRunner(IncidentCases cases, InvestigationHistory history, InvestigationModel model, InvestigationTools tools,
                TransactionTemplate transaction) {
        this.cases=cases; this.history=history; this.model=model; this.tools=tools; this.transaction=transaction;
    }

    enum Goal { AUTO, HISTORY, CURRENT_POS }

    InvestigationHistory.Report run(UUID caseId, DemoIdentity actor, String question, UUID parent, Goal goal, int steps) {
        validateSteps(steps);
        if (question == null || question.isBlank() || question.length() > 1600)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var incident = cases.read(caseId,actor);
        var mentioned = ORDER_IN_TEXT.matcher(question);
        while (mentioned.find()) if (!incident.orderId().equals(mentioned.group(1)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Open the case for the requested order");
        var memory = new RunMemory();
        memory.currentPos = goal == Goal.CURRENT_POS || (goal != Goal.HISTORY && currentPosQuestion(question));
        if (parent != null) {
            var prior = history.read(caseId,parent);
            if (InvestigationHistory.active(prior.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Resume the unfinished investigation first");
            memory.previousTurn = history.question(parent) + "\n上一轮结果（"+prior.status()+"）："+prior.answer();
            if (!memory.currentPos) memory.initial.addAll(RunMemory.selectLogs(question,incident.evidence().stream()));
        } else if (!memory.currentPos) {
            for (var item : incident.evidence()) {
                if (item.kind().equals("SNAPSHOT") && incident.observations().stream().anyMatch(
                        obs -> obs.source().equals(item.source()) && obs.observedAt().equals(item.collectedAt())))
                    memory.initial.add(item);
            }
        }
        UUID id = history.start(caseId,question,parent,actor.name(),memory);
        return drive(caseId,id,actor,steps,0,false);
    }

    static boolean currentPosQuestion(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        return (text.contains("现在") || text.contains("当前")) && (text.contains("pos") || text.contains("入库") || text.contains("有单"))
                && List.of("为什么","原因","第一次","最初","首次","昨天","那次","后来","之前","当时","补单","补推","能补","要补")
                        .stream().noneMatch(text::contains);
    }

    InvestigationHistory.Report resume(UUID caseId, UUID run, DemoIdentity actor, int steps) {
        validateSteps(steps);
        cases.scope(caseId,actor);
        var report=history.read(caseId,run);
        if (!InvestigationHistory.active(report.status())) return report;
        return drive(caseId,run,actor,steps,null,true);
    }

    private void validateSteps(int steps) {
        if (steps<0 || steps>MAX_TURNS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid step limit");
    }

    private InvestigationHistory.Report drive(UUID caseId, UUID run, DemoIdentity actor, int steps,
                                               Integer expectedVersion, boolean resumed) {
        for (int i=0; i<steps; i++) {
            cases.scope(caseId,actor);
            var claim=history.claim(caseId,run,actor.name(),expectedVersion);
            if (claim==null) break;
            var memory=claim.memory();
            if (resumed && i==0) memory.expireSnapshots();
            long started=System.nanoTime();
            InvestigationModel.Decision decision=null;
            String failure=null;
            if (!memory.currentPos) {
                var scope=cases.scope(caseId,actor);
                var ctx = new InvestigationModel.Context(claim.question(),scope.orderId(),"2026-11-18 Asia/Shanghai",
                        List.copyOf(memory.seen().values()),List.copyOf(memory.feedback),List.copyOf(memory.exchanges),
                        MAX_TOOLS-memory.toolCalls,memory.previousTurn);
                try {
                    decision=model.choose(ctx,tools.schemas(),Duration.ofMillis(claim.allowance()));
                } catch (InvestigationModel.Unavailable e) { failure=e.getMessage(); }
            }
            var choice=decision;
            var error=failure;
            boolean done=Boolean.TRUE.equals(transaction.execute(tx -> {
                history.fence(run,claim.turn());
                var scope=cases.scope(caseId,actor);
                return commitTurn(caseId,run,actor,scope,claim,choice,error,started);
            }));
            if (done) break;
            expectedVersion=claim.turn();
        }
        return history.read(caseId,run);
    }

    private boolean commitTurn(UUID caseId, UUID run, DemoIdentity actor, IncidentCases.Scope scope,
                               InvestigationHistory.Claim claim, InvestigationModel.Decision choice,
                               String failure, long started) {
        var memory=claim.memory();
        var seen=memory.seen();
        String status="PAUSED", answer="", hash="", result="OK";
        String action=choice==null ? "model" : choice.action();
        List<UUID> ids=List.of(), citations=List.of();
        try {
            if (memory.currentPos) {
                action="queryPosOrder";
                var args=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                var items=tools.execute(caseId,scope,actor,action,args,seen);
                memory.toolCalls++;
                memory.exchanges.add(new InvestigationModel.Exchange(action,args,items));
                var item=items.getFirst();
                ids=citations=List.of(item.id());
                answer=switch(item.excerpt()) {
                    case "FOUND" -> "本次查询已在 POS 查到该订单。";
                    case "NOT_FOUND" -> "本次查询在 POS 未找到该订单。";
                    default -> "本次 POS 查询返回 "+item.excerpt()+"，当前入库状态无法确认。";
                };
                status="FINISHED";
            } else if (failure!=null) {
                result="MODEL_UNAVAILABLE"; status="HANDOFF"; answer=failure+"，请按案件证据继续核查。";
            } else {
                var args=choice.args();
                var sorted=new TreeMap<String,JsonNode>();
                args.properties().forEach(e -> sorted.put(e.getKey(),e.getValue()));
                hash=DigestUtils.md5DigestAsHex(sorted.toString().getBytes(StandardCharsets.UTF_8));
                switch(action) {
                    case "finish" -> {
                        fields(args,"answer","citations");
                        citations=citations(args.path("citations"),seen);
                        answer=supportedAnswer(string(args,"answer"),citations,seen);
                        ids=citations; status="FINISHED";
                    }
                    case "clarify" -> {
                        fields(args,"question");
                        answer=string(args,"question"); status="NEEDS_INPUT";
                    }
                    default -> {
                        if (memory.toolCalls>=MAX_TOOLS || !memory.attempted.add(action+":"+hash)) {
                            status="HANDOFF"; result="BUDGET_OR_REPEAT"; answer="工具调用已重复或达到上限，请人工核查。";
                        } else {
                            memory.toolCalls++;
                            var items=tools.execute(caseId,scope,actor,action,args,seen);
                            memory.exchanges.add(new InvestigationModel.Exchange(action,args,items));
                            ids=items.stream().map(CaseEvidence.Item::id).toList();
                            memory.feedback.add(action+" => "+ids);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            result="INVALID_ACTION";
        } catch (ResponseStatusException e) {
            if (!e.getStatusCode().equals(HttpStatus.BAD_REQUEST)) throw e;
            result="INVALID_ACTION";
        }
        if (result.equals("INVALID_ACTION")) {
            memory.feedback.add("动作被拒绝：请核对函数名称、参数、时间窗及证据ID。");
            if (++memory.invalid>=2) {status="HANDOFF"; answer="连续无效动作，请人工复核。";}
        }
        if (status.equals("PAUSED") && claim.turn()==MAX_TURNS) {
            status="HANDOFF"; answer="调查轮次已用尽，请人工复核。";
        }
        if (status.equals("HANDOFF")) citations=List.copyOf(memory.seen().keySet());
        long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();
        history.step(run,new InvestigationHistory.Step(claim.turn(),action,hash,result,ids,
                elapsed,choice==null ? 0 : choice.tokens(),actor.name()));
        history.checkpoint(run,claim,elapsed);
        history.end(run,status,answer,citations);
        return !status.equals("PAUSED");
    }

    static String supportedAnswer(String proposed, List<UUID> citations, Map<UUID,CaseEvidence.Item> seen) {
        var cited = citations.stream().map(seen::get).toList();
        var requests = cited.stream().filter(e -> e.kind().equals("LOG") && e.source().equals("GATEWAY"))
                .map(CaseEvidence.Item::requestRef).distinct().toList();
        var missing = requests.stream().filter(ref -> cited.stream().noneMatch(e -> e.kind().equals("LOG")
                && e.source().equals("POS_WRITER") && Objects.equals(ref,e.requestRef()))).toList();
        boolean processingSearch = seen.values().stream().anyMatch(e -> e.kind().equals("SEARCH") && e.source().equals("POS_WRITER"));
        boolean noHandlingRecord = cited.stream().noneMatch(e -> e.kind().equals("LOG") && e.source().equals("POS_WRITER"));
        if (!missing.isEmpty() || (processingSearch && noHandlingRecord)) {
            return (missing.isEmpty() ? "已查询本单。" : "接收日志记录了请求 " + String.join("、",missing) + "。")
                    + "引用证据中缺少对应的写库处理记录，无法确认当次写入结果或失败原因。";
        }
        return proposed;
    }

    private List<UUID> citations(JsonNode array, Map<UUID,CaseEvidence.Item> seen) {
        if (!array.isArray() || array.isEmpty() || array.size()>16) throw new IllegalArgumentException();
        var verified = new LinkedHashSet<UUID>();
        for (var node : array) {
            UUID id = UUID.fromString(node.asText());
            if (!seen.containsKey(id)) throw new IllegalArgumentException();
            verified.add(id);
        }
        return List.copyOf(verified);
    }
}
