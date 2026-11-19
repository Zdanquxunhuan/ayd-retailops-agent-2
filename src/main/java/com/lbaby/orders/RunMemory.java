package com.lbaby.orders;

import java.util.*;
import java.util.stream.Stream;

/** Bounded state of one investigation. The report and complete evidence timeline remain separately readable. */
class RunMemory {
    public List<CaseEvidence.Item> initial = new ArrayList<>();
    public List<InvestigationModel.Exchange> exchanges = new ArrayList<>();
    public List<String> feedback = new ArrayList<>();
    public Set<String> attempted = new LinkedHashSet<>();
    public int toolCalls;
    public int invalid;
    public String previousTurn = "";
    public boolean currentPos;

    Map<UUID,CaseEvidence.Item> seen() {
        var seen = new LinkedHashMap<UUID,CaseEvidence.Item>();
        initial.forEach(e -> seen.put(e.id(),e));
        exchanges.forEach(x -> x.observations().forEach(e -> seen.put(e.id(),e)));
        return seen;
    }

    void expireSnapshots() {
        initial.removeIf(e -> e.kind().equals("SNAPSHOT"));
        var liveReads = Set.of("queryPosOrder","queryMeituanOrder","checkRepairEligibility");
        exchanges.removeIf(x -> liveReads.contains(x.name()));
        attempted.removeIf(k -> liveReads.stream().anyMatch(action -> k.startsWith(action + ":")));
    }

    static List<CaseEvidence.Item> selectLogs(String question, Stream<CaseEvidence.Item> candidates) {
        // Keep raw source IDs, event timestamps and request references when trimming history.
        var logs = candidates.filter(e -> e.kind().equals("LOG")).distinct().toList();
        var focus = new HashSet<String>();
        for (var e : logs) {
            if (relevance(question,e)>0 && e.requestRef()!=null) focus.add(e.requestRef());
        }
        return logs.stream().filter(e -> focus.isEmpty() || focus.contains(e.requestRef()))
                .sorted(Comparator.<CaseEvidence.Item>comparingInt(e -> relevance(question,e)).reversed()
                        .thenComparing(CaseEvidence.Item::eventTime,Comparator.reverseOrder()))
                .limit(12).toList();
    }

    private static int relevance(String question, CaseEvidence.Item item) {
        if (item.requestRef() != null && question.contains(item.requestRef())) return 3;
        if ((question.contains("后来") || question.contains("补单")) && item.excerpt().contains("补单")) return 2;
        if (question.contains("首次") && item.excerpt().contains("首次")) return 2;
        return 0;
    }
}
