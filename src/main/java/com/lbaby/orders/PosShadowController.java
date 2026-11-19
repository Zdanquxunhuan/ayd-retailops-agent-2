package com.lbaby.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Profile("pos-shadow")
@RestController
class PosShadowController {
    private final byte[] authorization;
    private final PosShadowLookup lookup;

    PosShadowController(@Value("${POS_DB_PASSWORD}") String password,
                        @Value("${POS_SHADOW_TOKEN}") String token) {
        if (token.isBlank() || password.isBlank()) throw new IllegalArgumentException("Missing POS shadow credentials");
        authorization=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        // Keep application schema initialization and repair state on the separate Spring datasource.
        lookup=new PosShadowLookup(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/mtjd?connectTimeout=3000&socketTimeout=6000",
                "onlyselect",password));
    }

    @PostMapping("/pos-shadow/orders/check")
    ResponseEntity<PosShadowLookup.Observation> check(@RequestHeader(value="Authorization",required=false) String auth,
                                                      @RequestBody CaseController.CreateRequest request) {
        if (auth==null || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var order=request.orderId();
        if (order==null || order.isBlank() || order.length()>64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var observation=lookup.query(order.strip());
        var code=switch(observation.status()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case DENIED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(observation);
    }
}
