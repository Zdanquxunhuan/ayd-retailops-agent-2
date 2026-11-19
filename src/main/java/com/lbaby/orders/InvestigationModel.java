package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

interface InvestigationModel {
    record Decision(String action, JsonNode args, int tokens) {}
    record Exchange(String name, JsonNode arguments, List<CaseEvidence.Item> observations) {}
    record Context(String question, String orderId, String fixtureDay, List<CaseEvidence.Item> evidence,
                   List<String> feedback, List<Exchange> exchanges, int remainingTools, String previousTurn) {}
    Decision choose(Context context, List<Map<String,Object>> tools, Duration timeout);

    class Unavailable extends RuntimeException {
        Unavailable(String safeMessage) { super(safeMessage); }
    }
}
