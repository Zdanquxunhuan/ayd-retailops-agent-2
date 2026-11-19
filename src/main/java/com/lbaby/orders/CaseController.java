package com.lbaby.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
@org.springframework.context.annotation.Profile("!pos-shadow")
@RequestMapping("/cases")
class CaseController {
    record CreateRequest(String orderId) {}

    private final IncidentCases cases;

    CaseController(IncidentCases cases) {
        this.cases = cases;
    }

    @PostMapping
    ResponseEntity<IncidentCases.Incident> create(@RequestHeader(value = "Authorization", required = false) String token,
                                                  @RequestBody CreateRequest request) {
        var actor = DemoIdentity.requireOperator(token);
        if (request.orderId() == null || request.orderId().isBlank() || request.orderId().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var incident = cases.create(request.orderId().strip(), actor);
        return ResponseEntity.created(URI.create("/cases/" + incident.id())).body(incident);
    }

    @GetMapping("/{id}")
    IncidentCases.Incident read(@PathVariable UUID id,
                                @RequestHeader(value = "Authorization", required = false) String token) {
        return cases.read(id, DemoIdentity.requireOperator(token));
    }
}
