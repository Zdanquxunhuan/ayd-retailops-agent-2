package com.lbaby.orders;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
class RuleBaseline {
    enum Outcome {
        POS_PRESENT, MISSING_CANDIDATE, MEITUAN_NOT_COMPLETED, MEITUAN_NOT_FOUND,
        POS_TIMEOUT, POS_DENIED, MEITUAN_TIMEOUT, MEITUAN_DENIED
    }
    record Observation(String source, String orderId, String storeId, String status, Instant observedAt) {}
    record Result(Outcome outcome, List<Observation> observations) {}

    private final OrderSources sources;

    RuleBaseline(OrderSources sources) {
        this.sources = sources;
    }

    Result evaluate(String orderId, String storeId) {
        var observations = new ArrayList<Observation>();
        var pos = sources.queryPos(orderId);
        observations.add(new Observation("POS_FIXTURE", orderId, storeId, pos.name(), Instant.now()));
        Outcome outcome = switch (pos) {
            case FOUND -> Outcome.POS_PRESENT;
            case DENIED -> Outcome.POS_DENIED;
            case TIMEOUT -> Outcome.POS_TIMEOUT;
            case NOT_FOUND -> {
                var mt = sources.queryMeituan(orderId);
                observations.add(new Observation("MEITUAN_FIXTURE", orderId, storeId, mt.name(), Instant.now()));
                yield switch (mt) {
                    case COMPLETED -> Outcome.MISSING_CANDIDATE;
                    case NOT_COMPLETED -> Outcome.MEITUAN_NOT_COMPLETED;
                    case NOT_FOUND -> Outcome.MEITUAN_NOT_FOUND;
                    case TIMEOUT -> Outcome.MEITUAN_TIMEOUT;
                    case DENIED -> Outcome.MEITUAN_DENIED;
                };
            }
        };
        return new Result(outcome, List.copyOf(observations));
    }
}
