package com.lbaby.orders;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
class RepairEligibility {
    record Assessment(String reason, List<RuleBaseline.Observation> observations) {
        public boolean isEligible() { return reason.equals(RuleBaseline.Outcome.MISSING_CANDIDATE.name()); }
    }

    private final OrderSources sources;
    private final RuleBaseline baseline;
    private final CaseEvidence evidence;

    RepairEligibility(OrderSources sources, RuleBaseline baseline, CaseEvidence evidence) {
        this.sources = sources;
        this.baseline = baseline;
        this.evidence = evidence;
    }

    Assessment check(IncidentCases.Scope scope) {
        if (!scope.channel().equals("MEITUAN") ||
                sources.storeOf(scope.orderId()).filter(scope.storeId()::equals).isEmpty()) {
            return new Assessment("ORDER_SCOPE_CHANGED", List.of());
        }
        var result = baseline.evaluate(scope.orderId(), scope.storeId());
        return new Assessment(result.outcome().name(), result.observations());
    }

    List<CaseEvidence.Item> save(UUID caseId, String reference, Assessment assessment) {
        return assessment.observations().stream().map(obs -> evidence.save(caseId, reference, obs.source(),
                "SNAPSHOT", reference, null, obs.observedAt(), obs.status())).toList();
    }
}
