package com.lbaby.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Profile("pos-shadow")
class ShadowCases {
    enum Intent {CURRENT,FIRST,LATEST,COMPARE,LATEST_FAILURE,COMPARE_FAILURES,CLARIFY}
    record Input(LocalDate date,String question,Intent intent,boolean refreshHistory) {}
    record Turn(int number,Instant at,String question,Intent intent,String planner,LocalDate archiveDate,
                PosShadowLookup.Observation pos,MeituanShadowLookup.Fact meituan,
                Integer archiveReadTurn,ArchiveTraceAgent.Report archiveSnapshot,ArchiveTraceAgent.Report history,
                List<String> steps,List<String> gaps,String answer) {}
    record View(UUID id,String orderId,List<Turn> turns) {}
    private record Plan(Intent intent,String source) {}
    private static final Map<Intent,String> DESCRIPTIONS=Map.of(
            Intent.CURRENT,"当前入库状态和缺单排查",Intent.FIRST,"最早一次历史回传",
            Intent.LATEST,"最近一次历史回传",Intent.COMPARE,"比较归档中的回传",
            Intent.LATEST_FAILURE,"最近一次失败的异常",Intent.COMPARE_FAILURES,"比较失败原因",
            Intent.CLARIFY,"无法确定问题或要求执行写操作");
    private static final List<Map<String,Object>> TOOLS=DESCRIPTIONS.entrySet().stream().map(e ->
            Map.<String,Object>of("type","function","function",Map.of("name",e.getKey().name(),
                    "description",e.getValue(),"parameters",Map.of("type","object","properties",Map.of(),
                    "additionalProperties",false)))).toList();
    private static final Pattern ORDER=Pattern.compile("(?<![0-9])[0-9]{15,64}(?![0-9])");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PosShadowLookup pos;
    private final MeituanShadowLookup mt;
    private final ArchiveTraceAgent archives;
    private final QwenModel model;
    private final String allowedOrder;

    ShadowCases(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,PosShadowLookup pos,MeituanShadowLookup mt,
                ArchiveTraceAgent archives,QwenModel model,@Value("${SHADOW_MT_ORDER_ID:}") String allowedOrder) {
        this.db=db; this.tx=tx; this.json=json; this.pos=pos; this.mt=mt;
        this.archives=archives; this.model=model; this.allowedOrder=allowedOrder;
    }

    View open(String order,Input input) { return append(new View(UUID.randomUUID(),order,List.of()),input); }
    View follow(UUID id,Input input) { return append(read(id),input); }

    View read(UUID id) {
        var orders=db.query("SELECT order_id FROM shadow_work_order WHERE id=?",(r,i)->r.getString(1),id);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var turns=db.query("SELECT details FROM shadow_work_turn WHERE case_id=? ORDER BY turn_no",
                (r,i)->decode(r.getString(1)),id);
        return new View(id,orders.getFirst(),turns);
    }

    private Turn decode(String encoded) {
        try { return json.readValue(encoded,Turn.class); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private View append(View view,Input input) {
        String question=Objects.toString(input.question(),"").strip();
        if (question.length()>256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (ORDER.matcher(question).results().anyMatch(m->!m.group().equals(view.orderId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Order differs from case");
        var previous=view.turns().isEmpty() ? null : view.turns().getLast();
        var plan=plan(input,question,previous);
        int number=view.turns().size()+1;
        var turn=investigate(view,input,question,plan,number,previous);
        String encoded;
        try { encoded=json.writeValueAsString(turn); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        tx.executeWithoutResult(s->{
            if (number==1) db.update("INSERT INTO shadow_work_order(id,order_id,version) VALUES(?,?,0)",view.id(),view.orderId());
            if (db.update("UPDATE shadow_work_order SET version=? WHERE id=? AND version=?",number,view.id(),number-1)!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Concurrent turn; reload the case");
            db.update("INSERT INTO shadow_work_turn(case_id,turn_no,details) VALUES(?,?,?)",view.id(),number,encoded);
        });
        var turns=new ArrayList<>(view.turns()); turns.add(turn);
        return new View(view.id(),view.orderId(),List.copyOf(turns));
    }

    private Plan plan(Input input,String q,Turn previous) {
        if (input.intent()!=null) return new Plan(input.intent(),"EXPLICIT");
        if (q.isEmpty()) {
            if (previous!=null && previous.intent()==Intent.CURRENT && previous.archiveDate()==null &&
                    input.date()!=null && previous.gaps().contains("请提供要查询的回传归档日期。"))
                return new Plan(Intent.COMPARE,"LOCAL");
            return new Plan(previous==null ? Intent.CURRENT : previous.intent(),"LOCAL");
        }
        try {
            var decision=model.selectCaseTool(q.replaceAll("[0-9]+","[编号]"),
                    previous==null ? "" : previous.intent().name(),TOOLS);
            if (decision.args().isObject() && decision.args().isEmpty())
                return new Plan(Intent.valueOf(decision.action()),"QWEN_FLASH");
            return new Plan(Intent.CLARIFY,"QWEN_FLASH");
        } catch (IllegalArgumentException e) { return new Plan(Intent.CLARIFY,"QWEN_FLASH"); }
        catch (InvestigationModel.Unavailable e) { return new Plan(offline(q),"LOCAL_FALLBACK"); }
    }

    private Intent offline(String q) {
        if (q.contains("首次") || q.contains("最早") || q.contains("第一次")) return Intent.FIRST;
        if (q.contains("最后") || q.contains("最近") || q.contains("后来"))
            return q.contains("一次失败") ? Intent.LATEST_FAILURE : Intent.LATEST;
        if (q.contains("比较") || q.contains("几次") || q.contains("四次"))
            return q.contains("失败") ? Intent.COMPARE_FAILURES : Intent.COMPARE;
        if (q.contains("现在") || q.contains("当前") || q.contains("没进") || q.contains("查不到")) return Intent.CURRENT;
        return Intent.CLARIFY;
    }

    private Turn investigate(View view,Input input,String question,Plan plan,int number,Turn previous) {
        var steps=new ArrayList<String>();
        var gaps=new ArrayList<String>();
        LocalDate date=input.date()!=null ? input.date() : previous==null ? null : previous.archiveDate();
        PosShadowLookup.Observation current=null;
        MeituanShadowLookup.Fact trade=null;
        ArchiveTraceAgent.Report snapshot=null,history=null;
        Integer origin=null;
        StringBuilder answer=new StringBuilder();
        boolean needsHistory=plan.intent()!=Intent.CURRENT && plan.intent()!=Intent.CLARIFY;
        if (plan.intent()==Intent.CLARIFY) gaps.add("请指定要查当前状态、首次回传、最近一次或比较失败原因。");
        if (plan.intent()==Intent.CURRENT || (number==1 && needsHistory)) {
            current=pos.query(view.orderId()); steps.add("queryPosOrder");
            answer.append(switch(current.status()) {
                case FOUND -> "本次 POS 查询中，支付和销售流水均存在。";
                case NOT_FOUND -> "本次 POS 查询中，两张表均未查到该单。";
                case PARTIAL -> "本次 POS 查询仅发现一类流水，需核对流水完整性。";
                default -> "POS 查询返回 "+current.status()+"，当前入库状态未知。";
            });
            if (plan.intent()==Intent.CURRENT && current.status()==PosShadowLookup.Status.NOT_FOUND) {
                if (view.orderId().equals(allowedOrder)) {
                    trade=mt.query(view.orderId()); steps.add("queryMeituanDetail");
                    answer.append(" 美团查证结果为 ").append(trade.state()).append("。");
                    if (trade.state()==MeituanShadowLookup.State.OUTSIDE_QUERY_WINDOW)
                        gaps.add("美团返回 838，已超出五天查询范围，交易状态未知。");
                    else if (trade.state()!=MeituanShadowLookup.State.COMPLETED && trade.state()!=MeituanShadowLookup.State.NOT_COMPLETED)
                        gaps.add("美团交易状态无法确认。");
                    if (date==null && trade.completedAt()!=null)
                        date=trade.completedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
                } else gaps.add("这笔订单未配置美团查询授权，交易状态待核实。");
                needsHistory=trade==null || trade.state()!=MeituanShadowLookup.State.NOT_COMPLETED;
            }
        }
        if (needsHistory && date==null) gaps.add("请提供要查询的回传归档日期。");
        if (needsHistory && date!=null) {
            var cached=input.refreshHistory() ? null : cached(view,date);
            ArchiveTraceAgent.Report evidence;
            if (cached==null) {
                snapshot=archives.trace(view.orderId(),date,null);
                evidence=snapshot; origin=number; steps.add("scanArchive");
            } else {
                evidence=cached.archiveSnapshot(); origin=cached.number(); steps.add("reuseArchive");
            }
            String tool=switch(plan.intent()) {
                case FIRST -> "firstAttempt"; case LATEST -> "latestAttempt"; case LATEST_FAILURE -> "latestFailure";
                case COMPARE_FAILURES -> "compareFailures"; default -> "compareAttempts";
            };
            history=archives.summarize(evidence.coverage(),evidence.attempts(),plan.source(),tool);
            steps.add(tool); answer.append(" ").append(history.answer());
            if (history.coverage().status()!=PushArchiveLookup.Status.SCANNED)
                gaps.add(date+" 归档覆盖不足："+history.coverage().status());
            else if (history.observedAttempts()==0) gaps.add(date+" 已读取的归档中未定位到该订单的回传。");
        }
        if (!gaps.isEmpty()) answer.append(" ").append(String.join(" ",gaps));
        return new Turn(number,Instant.now(),question,plan.intent(),plan.source(),date,current,trade,origin,snapshot,
                history,List.copyOf(steps),List.copyOf(gaps),answer.toString().strip());
    }

    private Turn cached(View view,LocalDate date) {
        for (var turn:view.turns().reversed()) {
            if (turn.archiveSnapshot()!=null && date.equals(turn.archiveDate()))
                return turn.archiveSnapshot().coverage().status()==PushArchiveLookup.Status.SCANNED ? turn : null;
        }
        return null;
    }
}
