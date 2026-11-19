package com.lbaby.orders;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
class FixtureOrderLogs implements OrderLogSource {
    record Scope(String orderId, String storeId, String source) {}
    // Only the reviewed operational excerpt can cross the fixture boundary.
    @JsonIgnoreProperties("privateFields")
    record Entry(String id, String orderId, String storeId, String source, String requestRef,
                 Instant time, String safeMessage) {}
    record Dataset(List<Scope> timeouts, List<Entry> events) {}

    private final Dataset data;

    FixtureOrderLogs(ObjectMapper mapper, @org.springframework.beans.factory.annotation.Value("${fixture.logs:fixtures/v1.2/logs.json}") String fixturePath) throws IOException {
        try (var in = new ClassPathResource(fixturePath).getInputStream()) {
            data = mapper.readValue(in, Dataset.class);
        }
    }

    public Page search(String orderId, String storeId, String source, Instant from, Instant until,
                       String requestRef, int limit) {
        if (data.timeouts().contains(new Scope(orderId, storeId, source))) {
            return new Page(true, false, List.of());
        }
        var matches = data.events().stream()
                .filter(e -> orderId.equals(e.orderId()) && storeId.equals(e.storeId()) && source.equals(e.source()))
                .filter(e -> !e.time().isBefore(from) && e.time().isBefore(until))
                .filter(e -> requestRef == null || requestRef.equals(e.requestRef()))
                .sorted(Comparator.comparing(Entry::time).thenComparing(Entry::id))
                .limit((long) limit + 1)
                .map(e -> new Event(e.id(), e.source(), e.requestRef(), e.time(), e.safeMessage())).toList();
        return new Page(false, matches.size() > limit, matches.subList(0, Math.min(limit, matches.size())));
    }
}
