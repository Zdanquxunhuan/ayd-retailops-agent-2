package com.lbaby.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record DemoIdentity(String name, String storeId) {
    static DemoIdentity requireOperator(String authorization) {
        if (authorization == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return switch (authorization) {
            case "Bearer demo-ops-a" -> new DemoIdentity("duty-a", "033101");
            case "Bearer demo-ops-a-next" -> new DemoIdentity("next-shift-a", "033101");
            case "Bearer demo-ops-b" -> new DemoIdentity("duty-b", "033102");
            case "Bearer demo-viewer" -> throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            default -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        };
    }
}
