package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Loopback protocol for the isolated push-center integration environment. */
@Primary
@Profile("repair-lab & !pos-shadow")
@RestController
class RepairLabTransport implements SynchronousRepair, OrderSources {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final URI base;
    private final String authorization;
    private final String environment;
    private final FixtureOrderSources fixtures;

    RepairLabTransport(ObjectMapper json, JdbcTemplate db, FixtureOrderSources fixtures,
                       @Value("${repair.lab-port}") int port, @Value("${REPAIR_LAB_TOKEN}") String token,
                       @Value("${repair.fixture-environment}") String name) {
        if (token.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Missing lab service configuration");
        this.base = URI.create("http://127.0.0.1:" + port);
        this.authorization = "Bearer " + token;
        this.environment = "lab:" + name;
        this.fixtures = fixtures;
        this.json = json;
        this.db = db;
    }

    public boolean enabled() { return true; }
    public String environment() { return environment; }
    public String posEvidenceSource() { return evidenceSource(); }
    public String evidenceSource() { return "LAB_POS"; }

    // The isolated receiver provides a single synthetic completed order.
    public Optional<String> storeOf(String order) {
        return "123456".equals(order) ? fixtures.storeOf(order) : Optional.empty();
    }
    public MeituanStatus queryMeituan(String order) { return fixtures.queryMeituan(order); }

    public PosStatus queryPos(String order) {
        var store = storeOf(order);
        if (store.isEmpty()) return PosStatus.DENIED;
        try {
            var response = send("/lab/pos/" + store.get() + "/" + order, false);
            if (response.statusCode() == 403) return PosStatus.DENIED;
            if (response.statusCode() != 200) return PosStatus.TIMEOUT;
            JsonNode ledger = json.readTree(response.body());
            if (ledger == null || !order.equals(ledger.path("orderId").asText())
                    || !store.get().equals(ledger.path("storeId").asText())
                    || !ledger.path("pay").isBoolean() || !ledger.path("sale").isBoolean()) return PosStatus.DENIED;
            boolean pay = ledger.get("pay").booleanValue(), sale = ledger.get("sale").booleanValue();
            if (pay != sale) return PosStatus.PARTIAL;
            return pay ? PosStatus.FOUND : PosStatus.NOT_FOUND;
        } catch (IOException e) {
            return PosStatus.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PosStatus.TIMEOUT;
        }
    }

    public Reply pushConfirmed(UUID attempt, String order) {
        try {
            var response = send("/lab/repairs/" + attempt, true);
            return response.statusCode() == 200 ? Reply.ACKNOWLEDGED : Reply.UNKNOWN;
        } catch (IOException e) {
            return Reply.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.UNKNOWN;
        }
    }

    private HttpResponse<String> send(String path, boolean write) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization);
        if (write) request.POST(HttpRequest.BodyPublishers.noBody());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    record Grant(String orderId, String storeId) {}
    @GetMapping("/internal/repair-lab/grants/{id}")
    Grant grant(@PathVariable UUID id, @RequestHeader(value="Authorization", defaultValue="") String auth) {
        if (!MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return db.query("""
                SELECT a.order_id,a.store_id FROM repair_attempt a
                JOIN repair_execution e ON e.attempt_id=a.id AND e.environment=a.environment
                JOIN demo_operator_grant g ON g.operator_name=a.confirmed_by AND g.store_id=a.store_id
                WHERE a.id=? AND a.environment=? AND a.action=? AND a.channel='MEITUAN'
                AND a.confirmed_at IS NOT NULL AND a.confirmed_by=a.requested_by AND a.expires_at>CURRENT_TIMESTAMP
                AND a.status='PENDING_EXECUTION' AND e.phase='EXECUTING' AND e.active_order=a.order_id
                AND g.enabled AND g.can_request_repair
                """, (rs,i) -> new Grant(rs.getString(1),rs.getString(2)), id,environment,RepairRequests.ACTION)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
