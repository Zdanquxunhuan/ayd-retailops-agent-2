package com.lbaby.orders;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@org.springframework.context.annotation.Profile("!pos-shadow")
@RequestMapping("/cases/{caseId}/investigations")
class InvestigationController {
    record Request(String question, AgentRunner.Goal goal, Integer stepLimit) {
        int steps() { return stepLimit==null ? AgentRunner.MAX_TURNS : stepLimit; }
    }
    record Resume(Integer stepLimit) {}
    private final AgentRunner runner;
    private final IncidentCases cases;
    private final InvestigationHistory history;
    InvestigationController(AgentRunner runner, IncidentCases cases, InvestigationHistory history) {
        this.runner=runner; this.cases=cases; this.history=history;
    }

    @PostMapping
    InvestigationHistory.Report run(@PathVariable UUID caseId,@RequestBody Request request,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return runner.run(caseId,DemoIdentity.requireOperator(auth),request.question(),null,request.goal(),request.steps());
    }

    @PostMapping("/{id}/follow-ups")
    InvestigationHistory.Report follow(@PathVariable UUID caseId,@PathVariable UUID id,@RequestBody Request request,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return runner.run(caseId,DemoIdentity.requireOperator(auth),request.question(),id,request.goal(),request.steps());
    }

    @PostMapping("/{id}/resume")
    InvestigationHistory.Report resume(@PathVariable UUID caseId,@PathVariable UUID id,
            @RequestBody(required=false) Resume request,@RequestHeader(value="Authorization",required=false) String auth) {
        return runner.resume(caseId,id,DemoIdentity.requireOperator(auth),
                request==null || request.stepLimit()==null ? AgentRunner.MAX_TURNS : request.stepLimit());
    }

    @GetMapping
    java.util.List<InvestigationHistory.Summary> list(@PathVariable UUID caseId,
            @RequestHeader(value="Authorization",required=false) String auth) {
        cases.scope(caseId,DemoIdentity.requireOperator(auth));
        return history.list(caseId);
    }

    @GetMapping("/{id}")
    InvestigationHistory.Report read(@PathVariable UUID caseId,@PathVariable UUID id,
            @RequestHeader(value="Authorization",required=false) String auth) {
        cases.scope(caseId,DemoIdentity.requireOperator(auth));
        return history.read(caseId,id);
    }
}
