package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
class InvestigationTools {
    private final OrderLogInvestigation logs;
    private final CaseEvidence evidence;
    private final OrderSources orders;
    private final LogScenario scenario;
    private final RepairEligibility eligibility;

    InvestigationTools(OrderLogInvestigation logs, CaseEvidence evidence,
                       OrderSources orders, LogScenario scenario, RepairEligibility eligibility) {
        this.logs = logs;
        this.evidence = evidence;
        this.orders = orders;
        this.scenario = scenario;
        this.eligibility = eligibility;
    }

    List<Map<String,Object>> schemas() {
        Map<String,Object> text = Map.of("type","string");
        var search = new LinkedHashMap<String,Object>();
        search.put("source", Map.of("type","string","enum",scenario.sources()));
        search.put("from", Map.of("type","string","description","ISO 8601 起始时刻，带时区"));
        search.put("until", Map.of("type","string","description","结束时刻，不含，最多 " + scenario.maxWindow()));
        var tools = new ArrayList<Map<String,Object>>();
        tools.add(schema("queryPosOrder","查询本单当前 POS 状态",Map.of()));
        tools.add(schema("checkRepairEligibility","查本单此刻能否申请补单。返回业务资格、现场观测及适用的申请入口",Map.of()));
        tools.add(schema("queryMeituanOrder","仅返回当前交易状态；不含历史事件时间",Map.of()));
        tools.add(schema("searchOrderLogs","在本单指定来源和时间窗查日志", Map.copyOf(search)));
        if (scenario.followEnabled()) {
            search.put("anchor",Map.of("type","string","description","已看到的 LOG 证据 UUID，不是请求号"));
            tools.add(schema("followLogReference","沿已发现的请求查询处理记录，隔离同单不同尝试", search));
        }
        tools.add(schema("finish","提交有引用的结论；证据不足时写明不足",Map.of("answer",text,
                "citations",Map.of("type","array","items",text,"minItems",1))));
        tools.add(schema("clarify","询问一个无法从工具取得的关键信息",Map.of("question",text)));
        return tools;
    }

    private Map<String,Object> schema(String name, String description, Map<String,Object> properties) {
        return Map.of("type","function","function", Map.of("name",name,"description",description,
                "parameters",Map.of("type","object","properties",properties,"required",properties.keySet(),
                        "additionalProperties",false)));
    }

    List<CaseEvidence.Item> execute(UUID caseId, IncidentCases.Scope scope, DemoIdentity actor, String action,
                                    JsonNode args, Map<UUID,CaseEvidence.Item> seen) {
        if (action.equals("checkRepairEligibility")) {
            fields(args);
            var assessment = eligibility.check(scope);
            String reference = UUID.randomUUID().toString();
            var facts = new ArrayList<>(eligibility.save(caseId,reference,assessment));
            String explanation = assessment.isEligible()
                    ? "美团交易已完成且 POS 未查到订单，运维可申请补单：/cases/" + caseId + "/repair-attempts"
                    : "当前不具备补单条件，原因：" + assessment.reason();
            facts.add(evidence.save(caseId, reference,"REPAIR_RULE","ASSESSMENT",reference,null,
                    java.time.Instant.now(),explanation));
            return facts;
        }
        if (action.equals("queryPosOrder") || action.equals("queryMeituanOrder")) {
            fields(args);
            boolean pos = action.equals("queryPosOrder");
            String status = pos ? orders.queryPos(scope.orderId()).name() : orders.queryMeituan(scope.orderId()).name();
            return List.of(evidence.save(caseId, UUID.randomUUID().toString(), pos ? orders.posEvidenceSource() : "MEITUAN_FIXTURE",
                    "SNAPSHOT", null, null, java.time.Instant.now(), status));
        }
        UUID anchor = null;
        if (action.equals("followLogReference") && scenario.followEnabled()) {
            fields(args, "source", "from", "until", "anchor");
            anchor = UUID.fromString(string(args,"anchor"));
            if (!seen.containsKey(anchor) || !seen.get(anchor).kind().equals("LOG")) throw new IllegalArgumentException();
        } else if (action.equals("searchOrderLogs")) {
            fields(args, "source", "from", "until");
        } else throw new IllegalArgumentException();
        var search = new OrderLogInvestigation.Search(string(args,"source"), java.time.OffsetDateTime.parse(string(args,"from")).toInstant(),
                java.time.OffsetDateTime.parse(string(args,"until")).toInstant(), Math.min(8,scenario.maxResults()));
        var result = logs.search(caseId,actor,search,anchor);
        var items = new ArrayList<>(result.events());
        items.add(result.searchEvidence());
        return items;
    }

    static String string(JsonNode args, String name) {
        var value = args.path(name);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2000) throw new IllegalArgumentException();
        return value.asText();
    }

    static void fields(JsonNode args, String... names) {
        if (!args.isObject() || args.size() != names.length) throw new IllegalArgumentException();
        for (var name : names) if (!args.hasNonNull(name)) throw new IllegalArgumentException();
    }
}
