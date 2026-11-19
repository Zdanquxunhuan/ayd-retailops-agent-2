package com.lbaby.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;

/** Network-free control for the benchmark harness. Only visible tool observations are inspected. */
final class EvalReplay implements InvestigationModel {
    private final ObjectMapper json;
    EvalReplay(ObjectMapper json) {this.json=json;}

    public Decision choose(Context ctx,List<Map<String,Object>> tools,Duration timeout) {
        boolean canSearch=tools.size()>1;
        boolean current=ctx.question().contains("当前 POS") || ctx.question().contains("现在 POS");
        var facts=ctx.evidence();
        if (current) {
            var p=facts.stream().filter(e->e.source().equals("POS_FIXTURE")).findFirst().orElseThrow();
            return finish(switch (p.excerpt()) {case "FOUND"->"PRESENT"; case "NOT_FOUND"->"ABSENT"; default->"UNKNOWN";},p);
        }
        boolean repair=ctx.question().contains("后来补单");
        var anchor=facts.stream().filter(e->e.kind().equals("LOG") && e.source().equals("GATEWAY") &&
                e.excerpt().contains(repair ? "补单":"首次")).findFirst();
        Map<String,Object> args=new LinkedHashMap<>();
        args.put("from","2026-11-17T16:00:00Z"); args.put("until","2026-11-18T16:00:00Z");
        if (canSearch && ctx.exchanges().isEmpty()) {
            args.put("source","GATEWAY");
            return decision("searchOrderLogs",args);
        }
        if (canSearch && ctx.exchanges().size()==1 && anchor.isPresent()) {
            args.put("source","POS_WRITER"); args.put("anchor",anchor.get().id());
            return decision("followLogReference",args);
        }
        if (anchor.isPresent()) {
            var event=facts.stream().filter(e->e.kind().equals("LOG") && e.source().equals("POS_WRITER") &&
                    anchor.get().requestRef().equals(e.requestRef())).findFirst();
            if (event.isPresent()) {
                String msg=event.get().excerpt();
                String outcome=msg.contains("成功")?"SUCCESS":msg.contains("映射")?"MAPPING":
                        msg.contains("锁等待")?"LOCK":msg.contains("已存在")?"DUPLICATE":msg.contains("流水不同")?"LEDGER":"UNKNOWN";
                return finish(outcome,event.get());
            }
        }
        var receipt=facts.stream().filter(e->e.kind().equals("SEARCH") &&
                e.source().equals(anchor.isEmpty()?"GATEWAY":"POS_WRITER")).findFirst().orElseThrow();
        return finish("UNKNOWN",receipt);
    }
    private Decision finish(String label,CaseEvidence.Item item) {
        return decision("finish",Map.of("answer",label,"citations",List.of(item.id())));
    }
    private Decision decision(String name,Map<String,Object> args) {
        return new Decision(name,json.valueToTree(args),0);
    }
}
