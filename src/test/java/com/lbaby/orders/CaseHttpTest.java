package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:cases;DB_CLOSE_DELAY=-1")
class CaseHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoSpyBean FixtureOrderSources sources;

    HttpEntity<?> request(String token, Object body) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    ResponseEntity<JsonNode> create(String token, String order) {
        return http.exchange("/cases", HttpMethod.POST, request(token, Map.of("orderId", order)), JsonNode.class);
    }

    ResponseEntity<JsonNode> read(String token, String path) {
        return http.exchange(path, HttpMethod.GET, request(token, null), JsonNode.class);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/baseline/v1.1/expected.csv", numLinesToSkip = 1)
    void baselineRoundTrip(String order, String businessTruth, String expectedOutcome, int mtCalls) {
        String actor = order.equals("223456") ? "demo-ops-b" : "demo-ops-a";
        var response = create(actor, order);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var json = response.getBody();
        assertThat(json.path("outcome").asText()).as(businessTruth).isEqualTo(expectedOutcome);
        assertThat(json.path("orderId").asText()).isEqualTo(order);
        assertThat(json.path("observations").size()).isEqualTo(mtCalls + 1);
        assertThat(json.at("/observations/0/source").asText()).isEqualTo("POS_FIXTURE");
        var stored = read(actor, response.getHeaders().getLocation().getPath());
        assertThat(stored.getBody()).isEqualTo(json);
        verify(sources).queryPos(order);
        verify(sources, times(mtCalls)).queryMeituan(order);
    }

    @Test
    void foreignAndUnknownOrdersAreIndistinguishable() {
        assertThat(create("demo-ops-a", "223456").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create("demo-ops-a", "999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sources, never()).queryPos(anyString());
        verify(sources, never()).queryMeituan(anyString());
        var own = create("demo-ops-b", "223456");
        var denied = read("demo-ops-a", own.getHeaders().getLocation().getPath());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(denied.getBody().toString()).doesNotContain("223456", "POS_PRESENT", "033102");
        assertThat(read("demo-ops-a", "/cases/" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checksIdentityRoleAndInput() {
        assertThat(create(null, "123456").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(create("demo-viewer", "123456").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create("demo-ops-a", " ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(sources, never()).queryPos(anyString());
    }
}
