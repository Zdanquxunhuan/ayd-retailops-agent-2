package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:logs;DB_CLOSE_DELAY=-1")
class LogInvestigationHttpTest {
    static final String A = "demo-ops-a", B = "demo-ops-b";
    static final String FROM = "2026-11-18T10:00:00+08:00", UNTIL = "2026-11-18T11:00:00+08:00";
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    ResponseEntity<JsonNode> call(String path, String token, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, body == null ? HttpMethod.GET : HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    String create(String order, String actor) {
        var r = call("/cases", actor, Map.of("orderId", order));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getHeaders().getLocation().getPath();
    }

    Map<String,Object> query(String source, String from, String until, int limit) {
        return Map.of("source", source, "from", from, "until", until, "limit", limit);
    }

    JsonNode ok(String path, Object body) {
        var r = call(path, A, body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void separatesAttemptsAndPersistsOriginalSanitizedEvidence() {
        String c = create("123457", A);
        var gateway = ok(c + "/logs/search", query("GATEWAY", FROM, UNTIL, 8));
        assertThat(gateway.path("events").size()).isEqualTo(2);
        assertThat(gateway.toString()).doesNotContain("13800000000", "模拟地址A", "synthetic-private-token");
        var first = gateway.at("/events/0");
        String anchor = c + "/evidence/" + first.get("id").asText();
        var workerQuery = query("POS_WRITER", FROM, UNTIL, 8);
        var failed = ok(anchor + "/follow", workerQuery);
        assertThat(failed.path("events").size()).isEqualTo(1);
        assertThat(failed.at("/events/0/excerpt").asText()).contains("门店映射缺失");
        var second = gateway.at("/events/1");
        var repaired = ok(c + "/evidence/" + second.get("id").asText() + "/follow", workerQuery);
        assertThat(repaired.at("/events/0/excerpt").asText()).contains("补单写入 POS 成功");
        assertThat(ok(anchor, null)).isEqualTo(first);
        assertThat(ok(anchor + "/follow", workerQuery).at("/events/0")).isEqualTo(failed.at("/events/0"));
        var detail = ok(c, null);
        assertThat(detail.path("outcome").asText()).isEqualTo("POS_PRESENT");
        var events = detail.path("evidence").findValuesAsText("kind");
        assertThat(events.stream().filter("LOG"::equals).count()).isEqualTo(4);
        assertThat(events).contains("SNAPSHOT", "SEARCH");
        assertThat(detail.path("evidence").toString()).doesNotContain("synthetic-private-token", "其他门店");
    }

    @Test
    void sameReferenceCannotCrossOrdersStoresOrCases() {
        String c = create("123457", A);
        var receipt = ok(c + "/logs/search", query("GATEWAY", FROM, UNTIL, 1));
        var id = receipt.at("/events/0/id").asText();
        assertThat(receipt.path("truncated").asBoolean()).isTrue();
        String missing = create("123456", A);
        assertThat(call(missing + "/evidence/" + id + "/follow", A, query("POS_WRITER", FROM, UNTIL, 8))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(c + "/evidence/" + id, B, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String other = create("223456", B);
        assertThat(call(other + "/evidence/" + id, B, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var received = ok(missing + "/logs/search", query("GATEWAY", FROM, UNTIL, 8));
        var absent = ok(missing + "/evidence/" + received.at("/events/0/id").asText() + "/follow",
                query("POS_WRITER", FROM, UNTIL, 8));
        assertThat(absent.path("status").asText()).isEqualTo("NO_MATCH");
        assertThat(absent.path("events").size()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "123458,POS_WRITER,2026-11-18T02:00:00Z,2026-11-18T03:00:00Z,TIMEOUT,false",
        "123457,GATEWAY,2026-10-01T02:00:00Z,2026-10-01T03:00:00Z,EXPIRED,true",
        "123457,GATEWAY,2026-10-31T18:00:00Z,2026-11-01T03:00:00Z,NO_MATCH,true",
        "123457,GATEWAY,2026-11-18T02:01:00Z,2026-11-18T02:02:00Z,NO_MATCH,false"
    })
    void reportsCoverage(String order, String source, String from, String until, String status, boolean expired) {
        var c = create(order, A);
        var result = ok(c + "/logs/search", query(source, from, until, 10));
        assertThat(result.path("status").asText()).isEqualTo(status);
        assertThat(result.path("expired").asBoolean()).isEqualTo(expired);
        var receipt = result.get("searchEvidence");
        assertThat(ok(c + "/evidence/" + receipt.get("id").asText(), null)).isEqualTo(receipt);
    }

    @Test
    void readsLegacySnapshotWithoutRequeryingTodaysOrder() {
        UUID id = UUID.randomUUID();
        // A V1.1 record from a time when today's POS_PRESENT fixture was unreachable.
        jdbc.update("""
                INSERT INTO incident_case(id, channel, order_id, store_id, created_by, goal, outcome,
                    created_at, observations)
                VALUES (?, 'MEITUAN','123457','033101','duty-a','CHECK_POS_STATUS','POS_TIMEOUT',
                    CURRENT_TIMESTAMP, ?)
                """, id, """
                [{"source":"POS_FIXTURE","orderId":"123457","storeId":"033101",
                  "status":"TIMEOUT","observedAt":"2026-11-17T23:59:00Z"}]
                """);
        String c = "/cases/" + id;
        var first = ok(c, null);
        assertThat(first.at("/evidence/0/excerpt").asText()).isEqualTo("TIMEOUT");
        assertThat(first.at("/evidence/0/collectedAt").asText()).isEqualTo("2026-11-17T23:59:00Z");
        assertThat(first.at("/evidence/0/eventTime").isNull()).isTrue();
        assertThat(ok(c, null)).isEqualTo(first);
    }

    @Test
    void enforcesScenarioBoundsAndExposesOnlySupportedTools() {
        var c = create("123457", A);
        assertThat(ok(c + "/log-tools", null).path("tools").toString()).contains("searchOrderLogs","followLogReference");
        for (var invalid : java.util.List.of(query("UNKNOWN", FROM, UNTIL, 2), query("GATEWAY", FROM, UNTIL, 51),
                query("GATEWAY", UNTIL, FROM, 2), query("GATEWAY", FROM, "2026-11-20T00:00:00Z", 2))) {
            assertThat(call(c + "/logs/search", A, invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var snapshot = ok(c, null).at("/evidence/0/id").asText();
        assertThat(call(c + "/evidence/" + snapshot + "/follow", A, query("POS_WRITER", FROM, UNTIL, 1))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c + "/logs/search", B, query("GATEWAY", FROM, UNTIL, 1))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
