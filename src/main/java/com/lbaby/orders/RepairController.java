package com.lbaby.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@org.springframework.context.annotation.Profile("!pos-shadow")
@RequestMapping("/cases/{caseId}")
class RepairController {
    private final RepairRequests repairs;
    private final RepairEligibility eligibility;
    private final IncidentCases cases;
    private final RepairExecution execution;

    RepairController(RepairRequests repairs, RepairEligibility eligibility, IncidentCases cases, RepairExecution execution) {
        this.repairs = repairs;
        this.eligibility = eligibility;
        this.cases = cases;
        this.execution = execution;
    }

    @GetMapping("/repair-eligibility")
    RepairEligibility.Assessment check(@PathVariable UUID caseId,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return eligibility.check(cases.scope(caseId,DemoIdentity.requireOperator(auth)));
    }

    @PostMapping("/repair-attempts")
    ResponseEntity<RepairRequests.Attempt> create(@PathVariable UUID caseId,
            @RequestHeader(value="Authorization",required=false) String auth) {
        var attempt = repairs.create(caseId,DemoIdentity.requireOperator(auth));
        return ResponseEntity.created(URI.create("/cases/" + caseId + "/repair-attempts/" + attempt.id())).body(attempt);
    }

    @GetMapping("/repair-attempts")
    List<RepairRequests.Attempt> list(@PathVariable UUID caseId,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return repairs.list(caseId,DemoIdentity.requireOperator(auth));
    }

    @GetMapping("/repair-attempts/{id}")
    RepairRequests.Attempt read(@PathVariable UUID caseId,@PathVariable UUID id,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return repairs.read(caseId,id,DemoIdentity.requireOperator(auth));
    }

    @PostMapping("/repair-attempts/{id}/confirm")
    ResponseEntity<RepairRequests.Attempt> confirm(@PathVariable UUID caseId,@PathVariable UUID id,
            @RequestHeader(value="Authorization",required=false) String auth,@RequestBody RepairRequests.Target reviewed) {
        var actor = DemoIdentity.requireOperator(auth);
        var record = execution.afterConfirmation(repairs.confirm(caseId,id,actor,reviewed),actor);
        return ResponseEntity.status(record.status() == RepairRequests.Status.INVALIDATED
                ? HttpStatus.CONFLICT : HttpStatus.OK).body(record);
    }

    @PostMapping("/repair-attempts/{id}/verify")
    RepairRequests.Attempt verify(@PathVariable UUID caseId,@PathVariable UUID id,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return execution.verify(caseId,id,DemoIdentity.requireOperator(auth));
    }

    @ExceptionHandler(RepairRequests.Ineligible.class)
    ResponseEntity<RepairEligibility.Assessment> ineligible(RepairRequests.Ineligible failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(failure.assessment);
    }
}
