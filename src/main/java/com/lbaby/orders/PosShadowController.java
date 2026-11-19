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
    private final MeituanShadowLookup meituan;
    private final PushArchiveLookup archives;
    private final ArchiveTraceAgent traceAgent;

    PosShadowController(@Value("${POS_DB_PASSWORD}") String password,
                        @Value("${POS_SHADOW_TOKEN}") String token, MeituanShadowLookup meituan, PushArchiveLookup archives,
                        ArchiveTraceAgent traceAgent) {
        this.traceAgent=traceAgent;
        this.archives=archives;
        this.meituan=meituan;
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
        var observation=lookup.query(order(auth,request));
        return ResponseEntity.status(posCode(observation)).cacheControl(CacheControl.noStore()).body(observation);
    }

    record OrderCheck(PosShadowLookup.Observation pos, MeituanShadowLookup.Fact meituan) {}

    record LogSearch(String orderId, java.time.LocalDate date) {}

    record TraceRequest(String orderId,java.time.LocalDate date,String plannerQuestion) {}

    @PostMapping("/pos-shadow/orders/logs/trace")
    ResponseEntity<ArchiveTraceAgent.Report> trace(@RequestHeader(value="Authorization",required=false) String auth,
                                                   @RequestBody TraceRequest request) {
        var id=order(auth,new CaseController.CreateRequest(request.orderId()));
        if (request.date()==null || !id.matches("[0-9]{1,64}") ||
                (request.plannerQuestion()!=null && request.plannerQuestion().length()>256))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var report=traceAgent.trace(id,request.date(),request.plannerQuestion());
        var unavailable=report.coverage()!=null && (report.coverage().status()==PushArchiveLookup.Status.READ_ERROR ||
                report.coverage().status()==PushArchiveLookup.Status.NOT_CONFIGURED);
        return ResponseEntity.status(unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(report);
    }

    @PostMapping("/pos-shadow/orders/logs")
    ResponseEntity<PushArchiveLookup.Report> logs(@RequestHeader(value="Authorization",required=false) String auth,
                                                  @RequestBody LogSearch request) {
        var id=order(auth,new CaseController.CreateRequest(request.orderId()));
        if (request.date()==null || !id.matches("[0-9]{1,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var report=archives.search(request.date(),id);
        var code=switch(report.status()) {
            case READ_ERROR, NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(report);
    }

    @PostMapping("/pos-shadow/orders/investigate")
    ResponseEntity<OrderCheck> investigate(@RequestHeader(value="Authorization",required=false) String auth,
                                           @RequestBody CaseController.CreateRequest request) {
        var id=order(auth,request);
        var pos=lookup.query(id);
        var mt=pos.status()==PosShadowLookup.Status.NOT_FOUND ? meituan.query(id) : null;
        return ResponseEntity.status(mt==null ? posCode(pos) : mtCode(mt)).cacheControl(CacheControl.noStore())
                .body(new OrderCheck(pos,mt));
    }

    @PostMapping("/pos-shadow/orders/meituan-detail")
    ResponseEntity<MeituanShadowLookup.Fact> detail(@RequestHeader(value="Authorization",required=false) String auth,
                                                   @RequestBody CaseController.CreateRequest request) {
        var fact=meituan.query(order(auth,request));
        return ResponseEntity.status(mtCode(fact)).cacheControl(CacheControl.noStore()).body(fact);
    }

    private String order(String auth,CaseController.CreateRequest request) {
        if (auth==null || !MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (request.orderId()==null || request.orderId().isBlank() || request.orderId().length()>64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return request.orderId().strip();
    }

    private HttpStatus mtCode(MeituanShadowLookup.Fact fact) {
        return switch(fact.state()) {
            case NOT_CONFIGURED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_RESPONSE, API_ERROR -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.OK;
        };
    }

    private HttpStatus posCode(PosShadowLookup.Observation observation) {
        return switch(observation.status()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case DENIED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
    }
}
