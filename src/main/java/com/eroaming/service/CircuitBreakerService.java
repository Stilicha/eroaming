package com.eroaming.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class CircuitBreakerService {

    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    private static final long EVICTION_THRESHOLD_MS = 24 * 60 * 60 * 1000;

    public CircuitBreakerService() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .recordExceptions(IOException.class, TimeoutException.class, RuntimeException.class)
                .ignoreExceptions()
                .build();

        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultConfig);

        startCleanupScheduler();
    }

    public CircuitBreaker getCircuitBreaker(String partnerId) {
        lastAccessTimes.put(partnerId, System.currentTimeMillis());

        return circuitBreakers.computeIfAbsent(partnerId, id -> {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("partner-" + partnerId);

            circuitBreaker.getEventPublisher()
                    .onSuccess(event -> log.debug("Circuit breaker success for partner: {}", partnerId))
                    .onError(event -> log.debug("Circuit breaker error for partner: {}, error: {}",
                            partnerId, event.getThrowable().getMessage()))
                    .onStateTransition(event -> {
                        log.info("Circuit breaker state changed for partner {}: {} -> {}",
                                partnerId, event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState());
                    });

            return circuitBreaker;
        });
    }

    /**
     * Evicts circuit breakers that haven't been used recently
     */
    @Scheduled(fixedRate = 3600000)
    public void evictInactiveCircuitBreakers() {
        long now = System.currentTimeMillis();
        List<String> toEvict = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastAccessTimes.entrySet()) {
            if (now - entry.getValue() > EVICTION_THRESHOLD_MS) {
                toEvict.add(entry.getKey());
            }
        }

        for (String partnerId : toEvict) {
            circuitBreakers.remove(partnerId);
            lastAccessTimes.remove(partnerId);
            log.info("Evicted inactive circuit breaker for partner: {}", partnerId);
        }

        if (!toEvict.isEmpty()) {
            log.info("Evicted {} inactive circuit breakers", toEvict.size());
        }
    }

    private void startCleanupScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::evictInactiveCircuitBreakers, 1, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    public void cleanup() {
        circuitBreakers.clear();
        lastAccessTimes.clear();
    }
}