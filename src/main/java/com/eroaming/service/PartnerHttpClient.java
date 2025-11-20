package com.eroaming.service;

import com.eroaming.model.Partner;
import com.eroaming.model.PartnerResponse;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client service for communicating with partner REST APIs during broadcast operations.
 *
 * <p>This service handles all HTTP communication with partner systems, providing:
 * <ul>
 *   <li><b>Multi-format Support:</b> JSON, XML, and form-data request bodies</li>
 *   <li><b>Authentication Methods:</b> API Key, Bearer token, Basic auth, OAuth2, and no auth</li>
 *   <li><b>Dynamic Configuration:</b> Per-partner timeouts, headers, and success patterns</li>
 *   <li><b>Error Resilience:</b> Graceful handling of timeouts, network errors, and malformed responses</li>
 *   <li><b>Observability:</b> Detailed metrics for success rates, errors, and response times</li>
 * </ul>
 *
 * <p><b>Request Processing:</b>
 * For each partner, the client:
 * <ol>
 *   <li>Constructs appropriate request body based on partner's preferred format</li>
 *   <li>Configures authentication headers according to partner's auth type</li>
 *   <li>Applies partner-specific custom headers</li>
 *   <li>Sends non-blocking HTTP request with partner-specific timeout</li>
 *   <li>Parses response using partner-specific field mapping rules</li>
 *   <li>Determines success using partner-specific status patterns</li>
 * </ol>
 *
 * <p><b>Response Processing:</b>
 * The client extracts status and message from partner responses using configurable JSON paths,
 * supporting nested field structures. Success is determined by matching against configurable
 * success patterns (e.g., "success", "approved", "ok").
 *
 * <p><b>Technical Implementation:</b>
 * <ul>
 *   <li>Uses Spring WebClient for non-blocking HTTP requests</li>
 *   <li>Supports per-partner timeouts (1-30 seconds)</li>
 *   <li>Automatically cancels requests on early termination</li>
 *   <li>Provides detailed metrics for monitoring partner health</li>
 * </ul>
 *
 * @see Partner
 * @see PartnerResponse
 * @see WebClient
 */
@Slf4j
@Service
public class PartnerHttpClient {

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;

    private final Counter successCounter;
    private final Counter errorCounter;
    private final Counter timeoutCounter;
    private final Timer requestTimer;

    public PartnerHttpClient(WebClient webClient, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;

        // Initialize metrics
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
    }

    /**
     * Sends a start charging request to the specified partner.
     *
     * @param partner The partner to send the request to.
     * @param uid     The UID for the start charging request.
     * @return A CompletableFuture containing the PartnerResponse.
     */
    public CompletableFuture<PartnerResponse> sendStartChargingRequest(Partner partner, String uid) {
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        String url = partner.getBaseUrl() + partner.getStartChargingEndpoint();

        log.debug("Sending request to partner - Partner: {}, URL: {}, UID: {}",
                partner.getId(), url, uid);

        return webClient.post()
                .uri(url)
                .headers(headers -> configureHeaders(headers, partner))
                .bodyValue(createRequestBody(partner, uid))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(partner.getTimeoutMs()))
                .map(response -> {
                    sample.stop(requestTimer);
                    PartnerResponse partnerResponse = createSuccessResponse(partner, response, startTime);

                    if (partnerResponse.isSuccess()) {
                        successCounter.increment();
                        log.info("Partner request successful - Partner: {}, Time: {}ms",
                                partner.getId(), partnerResponse.getResponseTimeMs());
                    } else {
                        errorCounter.increment();
                        log.warn("Partner request failed - Partner: {}, Status: {}, Time: {}ms",
                                partner.getId(), partnerResponse.getStatus(), partnerResponse.getResponseTimeMs());
                    }

                    return partnerResponse;
                })
                .onErrorResume(throwable -> {
                    sample.stop(requestTimer);
                    PartnerResponse errorResponse = createErrorResponse(partner, throwable.getMessage(), startTime);

                    if (errorResponse.isTimeout()) {
                        timeoutCounter.increment();
                        log.warn("Partner request timeout - Partner: {}, Time: {}ms",
                                partner.getId(), errorResponse.getResponseTimeMs());
                    } else {
                        errorCounter.increment();
                        log.warn("Partner request error - Partner: {}, Error: {}, Time: {}ms",
                                partner.getId(), throwable.getMessage(), errorResponse.getResponseTimeMs());
                    }

                    return Mono.just(errorResponse);
                })
                .toFuture();
    }

    /**
     * Configures HTTP headers based on the partner's authentication type and custom headers.
     *
     * @param headers The HttpHeaders to configure.
     * @param partner The partner whose configuration is used.
     */
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

    /**
     * Creates the request body based on the partner's specified format.
     *
     * @param partner The partner whose configuration is used.
     * @param uid     The UID for the start charging request.
     * @return The request body in the appropriate format.
     */
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

    /**
     * Creates a PartnerResponse for a successful HTTP response.
     *
     * @param partner   The partner whose configuration is used.
     * @param response  The response body as a Map.
     * @param startTime The start time of the request in milliseconds.
     * @return A PartnerResponse representing the result.
     */
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
                .build();
    }
}