package com.lbaby.orders;

import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@org.springframework.context.annotation.Profile("!pos-shadow")
@RequestMapping("/cases/{caseId}")
class LogController {
    record Tools(List<String> tools, Set<String> sources, Duration maxWindow, int maxResults) {}

    private final IncidentCases cases;
    private final LogScenario scenario;
    private final OrderLogInvestigation investigation;
    private final CaseEvidence evidence;

    LogController(IncidentCases cases, LogScenario scenario, OrderLogInvestigation investigation,
                  CaseEvidence evidence) {
        this.cases = cases;
        this.scenario = scenario;
        this.investigation = investigation;
        this.evidence = evidence;
    }

    @GetMapping("/log-tools")
    Tools tools(@PathVariable UUID caseId, @RequestHeader(value="Authorization", required=false) String auth) {
        cases.scope(caseId, DemoIdentity.requireOperator(auth));
        var tools = scenario.followEnabled() ? List.of("searchOrderLogs", "followLogReference") : List.of("searchOrderLogs");
        return new Tools(tools, scenario.sources(), scenario.maxWindow(), scenario.maxResults());
    }

    @PostMapping("/logs/search")
    OrderLogInvestigation.Result search(@PathVariable UUID caseId, @RequestBody OrderLogInvestigation.Search query,
                                        @RequestHeader(value="Authorization", required=false) String auth) {
        return investigation.search(caseId, DemoIdentity.requireOperator(auth), query, null);
    }

    @PostMapping("/evidence/{id}/follow")
    OrderLogInvestigation.Result follow(@PathVariable UUID caseId, @PathVariable UUID id,
                                        @RequestBody OrderLogInvestigation.Search query,
                                        @RequestHeader(value="Authorization", required=false) String auth) {
        return investigation.search(caseId, DemoIdentity.requireOperator(auth), query, id);
    }

    @GetMapping("/evidence/{id}")
    CaseEvidence.Item read(@PathVariable UUID caseId, @PathVariable UUID id,
                           @RequestHeader(value="Authorization", required=false) String auth) {
        cases.scope(caseId, DemoIdentity.requireOperator(auth));
        return evidence.read(caseId, id);
    }
}
