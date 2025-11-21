package com.eroaming.service;

import com.eroaming.model.Partner;
import com.eroaming.model.PartnerResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class PartnerHttpClient {

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final CircuitBreakerService circuitBreakerService;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Counter timeoutCounter;
    private final Timer requestTimer;
    private final Counter circuitBreakerOpenCounter;
    private final Counter circuitBreakerSuccessCounter;
    private final Counter circuitBreakerFailureCounter;

    public PartnerHttpClient(WebClient webClient, MeterRegistry meterRegistry, CircuitBreakerService circuitBreakerService) {
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;
        this.circuitBreakerService = circuitBreakerService;

        this.successCounter = Counter.builder("partner.http.success")
                .description("Successful partner HTTP requests")
                .register(meterRegistry);

        this.errorCounter = Counter.builder("partner.http.errors")
                .description("Failed partner HTTP requests")
                .register(meterRegistry);

        this.timeoutCounter = Counter.builder("partner.http.timeouts")
                .description("Partner HTTP request timeouts")
                .register(meterRegistry);

        this.requestTimer = Timer.builder("partner.http.duration")
                .description("Partner HTTP request duration")
                .register(meterRegistry);

        this.circuitBreakerOpenCounter = Counter.builder("partner.circuitbreaker.open")
                .description("Circuit breaker opened events")
                .register(meterRegistry);

        this.circuitBreakerSuccessCounter = Counter.builder("partner.circuitbreaker.success")
                .description("Circuit breaker successful calls")
                .register(meterRegistry);

        this.circuitBreakerFailureCounter = Counter.builder("partner.circuitbreaker.failure")
                .description("Circuit breaker failed calls")
                .register(meterRegistry);
    }

    /**
     * Sends a start charging request to the specified partner with circuit breaker protection.
     */
    public CompletableFuture<PartnerResponse> sendStartChargingRequest(Partner partner, String uid) {
        CircuitBreaker circuitBreaker = circuitBreakerService.getCircuitBreaker(partner.getId());

        if (!circuitBreaker.tryAcquirePermission()) {
            circuitBreakerOpenCounter.increment();
            log.warn("Circuit breaker OPEN for partner {} - returning immediate fallback", partner.getId());
            return CompletableFuture.completedFuture(createCircuitBreakerFallback(partner));
        }

        long startTime = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        String url = partner.getBaseUrl() + partner.getStartChargingEndpoint();

        log.debug("Sending request to partner - Partner: {}, URL: {}, UID: {}, CircuitBreaker: {}",
                partner.getId(), url, uid, circuitBreaker.getState());

        return webClient.post()
                .uri(url)
                .headers(headers -> configureHeaders(headers, partner))
                .bodyValue(createRequestBody(partner, uid))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofMillis(partner.getTimeoutMs()))
                .map(response -> {
                    sample.stop(requestTimer);
                    PartnerResponse partnerResponse = createSuccessResponse(partner, response, startTime);

                    if (isTechnicalSuccess(partnerResponse)) {
                        circuitBreaker.onSuccess(partnerResponse.getResponseTimeMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                        circuitBreakerSuccessCounter.increment();
                    } else {
                        circuitBreaker.onSuccess(partnerResponse.getResponseTimeMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    }

                    if (partnerResponse.isSuccess()) {
                        successCounter.increment();
                        log.info("Partner request successful - Partner: {}, Time: {}ms, CircuitBreaker: {}",
                                partner.getId(), partnerResponse.getResponseTimeMs(), circuitBreaker.getState());
                    } else {
                        errorCounter.increment();
                        log.warn("Partner request business failure - Partner: {}, Status: {}, Time: {}ms, CircuitBreaker: {}",
                                partner.getId(), partnerResponse.getStatus(), partnerResponse.getResponseTimeMs(),
                                circuitBreaker.getState());
                    }

                    return partnerResponse;
                })
                .onErrorResume(throwable -> {
                    sample.stop(requestTimer);

                    long responseTime = System.currentTimeMillis() - startTime;

                    circuitBreaker.onError(responseTime, java.util.concurrent.TimeUnit.MILLISECONDS, throwable);
                    circuitBreakerFailureCounter.increment();

                    PartnerResponse errorResponse = createErrorResponse(partner, throwable.getMessage(), startTime);

                    if (errorResponse.isTimeout()) {
                        timeoutCounter.increment();
                        log.warn("Partner request timeout - Partner: {}, Time: {}ms, CircuitBreaker: {}",
                                partner.getId(), errorResponse.getResponseTimeMs(), circuitBreaker.getState());
                    } else {
                        errorCounter.increment();
                        log.warn("Partner request technical error - Partner: {}, Error: {}, Time: {}ms, CircuitBreaker: {}",
                                partner.getId(), throwable.getMessage(), errorResponse.getResponseTimeMs(),
                                circuitBreaker.getState());
                    }

                    return Mono.just(errorResponse);
                })
                .toFuture();
    }

    /**
     * Determines if the response represents a technical success (HTTP call completed without network errors).
     */
    private boolean isTechnicalSuccess(PartnerResponse response) {
        return !response.isTimeout() &&
                !response.isCircuitBreakerOpen() &&
                !"NETWORK_ERROR".equals(response.getStatus());
    }

    /**
     * Creates a fallback response when circuit breaker is open.
     */
    private PartnerResponse createCircuitBreakerFallback(Partner partner) {
        return PartnerResponse.builder()
                .partnerId(partner.getId())
                .success(false)
                .status("CIRCUIT_BREAKER_OPEN")
                .message("Service temporarily unavailable - circuit breaker open")
                .responseTimeMs(0)
                .timeout(false)
                .circuitBreakerOpen(true)
                .build();
    }

    private void configureHeaders(HttpHeaders headers, Partner partner) {
        headers.setContentType(MediaType.APPLICATION_JSON);

        switch (partner.getAuthenticationType().toUpperCase()) {
            case "API_KEY" -> headers.set("X-API-Key", partner.getApiKey());
            case "BEARER" -> headers.setBearerAuth(partner.getApiKey());
            case "BASIC" -> {
                String[] credentials = partner.getApiKey().split(":", 2);
                if (credentials.length == 2) {
                    headers.setBasicAuth(credentials[0], credentials[1]);
                } else {
                    log.warn("Invalid BASIC auth format for partner {}", partner.getId());
                }
            }
        }

        if (partner.getCustomHeaders() != null) {
            partner.getCustomHeaders().forEach(headers::set);
        }
    }

    private Object createRequestBody(Partner partner, String uid) {
        switch (partner.getRequestFormat().toUpperCase()) {
            case "JSON":
                Map<String, Object> jsonBody = new HashMap<>();
                jsonBody.put(partner.getUidFieldName(), uid);
                jsonBody.put("timestamp", Instant.now().toString());
                jsonBody.put("requestId", UUID.randomUUID().toString());
                return jsonBody;

            case "XML":
                return String.format(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                "<StartChargingRequest>" +
                                "<%1$s>%2$s</%1$s>" +
                                "<timestamp>%3$s</timestamp>" +
                                "<requestId>%4$s</requestId>" +
                                "</StartChargingRequest>",
                        partner.getUidFieldName(), uid, Instant.now(), UUID.randomUUID()
                );

            case "FORM_DATA":
                MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                formData.add(partner.getUidFieldName(), uid);
                formData.add("timestamp", Instant.now().toString());
                formData.add("requestId", UUID.randomUUID().toString());
                return formData;

            default:
                return Map.of(
                        partner.getUidFieldName(), uid,
                        "timestamp", Instant.now().toString(),
                        "requestId", UUID.randomUUID().toString()
                );
        }
    }

    private PartnerResponse createSuccessResponse(Partner partner, Map<String, Object> response, long startTime) {
        long responseTime = System.currentTimeMillis() - startTime;

        String status = extractFieldValue(partner.getResponseStatusPath(), response);
        String message = extractFieldValue(partner.getResponseMessagePath(), response);
        boolean success = isSuccessResponse(partner, status);

        log.debug("Partner {} - Status: '{}', Expected: '{}', Success: {}",
                partner.getId(), status, partner.getSuccessStatusPattern(), success);

        return PartnerResponse.builder()
                .partnerId(partner.getId())
                .success(success)
                .status(status)
                .message(message)
                .responseTimeMs(responseTime)
                .timeout(false)
                .circuitBreakerOpen(false)
                .build();
    }

    private PartnerResponse createErrorResponse(Partner partner, String error, long startTime) {
        long responseTime = System.currentTimeMillis() - startTime;
        boolean timeout = error.toLowerCase().contains("timeout");

        return PartnerResponse.builder()
                .partnerId(partner.getId())
                .success(false)
                .status("ERROR")
                .message(error)
                .responseTimeMs(responseTime)
                .timeout(timeout)
                .circuitBreakerOpen(false)
                .build();
    }

    private boolean isSuccessResponse(Partner partner, String actualStatus) {
        if (actualStatus == null) return false;

        String[] successPatterns = partner.getSuccessStatusPattern().split(",");
        for (String pattern : successPatterns) {
            if (pattern.trim().equalsIgnoreCase(actualStatus.trim())) {
                return true;
            }
        }
        return false;
    }

    private String extractFieldValue(String path, Map<String, Object> response) {
        if (path == null || path.isEmpty() || response == null) {
            return "N/A";
        }

        try {
            Object current = response;
            String[] pathParts = path.split("\\.");

            for (String part : pathParts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return "N/A";
                }
                if (current == null) return "N/A";
            }

            return String.valueOf(current);
        } catch (Exception e) {
            log.warn("Failed to extract field '{}' from response: {}", path, e.getMessage());
            return "EXTRACTION_ERROR";
        }
    }
}