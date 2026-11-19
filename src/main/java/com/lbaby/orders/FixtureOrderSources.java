package com.lbaby.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class FixtureOrderSources implements OrderSources {
    record Order(String orderId, String storeId, PosStatus pos, MeituanStatus meituan) {}
    private final Map<String, Order> orders;

    private final SandboxPos sandbox;

    FixtureOrderSources(ObjectMapper mapper, SandboxPos sandbox) throws IOException {
        this.sandbox = sandbox;
        var fixtures = new ClassPathResource("fixtures/v1.1/orders.json");
        try (var input = fixtures.getInputStream()) {
            orders = Arrays.stream(mapper.readValue(input, Order[].class))
                    .collect(Collectors.toUnmodifiableMap(Order::orderId, Function.identity()));
        }
    }

    public Optional<String> storeOf(String orderId) {
        return Optional.ofNullable(orders.get(orderId)).map(Order::storeId);
    }

    public PosStatus queryPos(String orderId) {
        if (sandbox.contains(orderId)) return PosStatus.FOUND;
        return orders.get(orderId).pos();
    }

    public MeituanStatus queryMeituan(String orderId) {
        return orders.get(orderId).meituan();
    }
}
