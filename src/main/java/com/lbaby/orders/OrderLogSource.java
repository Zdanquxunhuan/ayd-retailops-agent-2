package com.lbaby.orders;

import java.time.Instant;
import java.util.List;

interface OrderLogSource {
    record Event(String id, String source, String requestRef, Instant time, String safeMessage) {}
    record Page(boolean timedOut, boolean truncated, List<Event> events) {}

    Page search(String orderId, String storeId, String source, Instant from, Instant until,
                String requestRef, int limit);
}
