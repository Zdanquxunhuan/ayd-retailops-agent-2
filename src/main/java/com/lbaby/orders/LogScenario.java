package com.lbaby.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Configuration
@PropertySource("classpath:scenarios/meituan-orders-v1.2.properties")
@EnableConfigurationProperties(LogScenario.class)
class LogConfiguration {}

@ConfigurationProperties("order-logs")
record LogScenario(Set<String> sources, boolean followEnabled, Duration maxWindow, int maxResults,
                   Instant retainedFrom) {
    LogScenario {
        sources = Set.copyOf(sources);
        if (sources.isEmpty() || maxResults < 1 || maxWindow.isZero() || maxWindow.isNegative()
                || retainedFrom == null) throw new IllegalArgumentException("Invalid log scenario configuration");
    }
}
