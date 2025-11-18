package com.eroaming.service;

import com.eroaming.model.Partner;
import com.eroaming.model.PartnerResponse;
import lombok.RequiredArgsConstructor;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerHttpClient {

    private final WebClient webClient;

    public CompletableFuture<PartnerResponse> sendStartChargingRequest(Partner partner, String uid) {
        long startTime = System.currentTimeMillis();

        String url = partner.getBaseUrl() + partner.getStartChargingEndpoint();

        return webClient.post()
                .uri(url)
                .headers(headers -> configureHeaders(headers, partner))
                .bodyValue(createRequestBody(partner, uid))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(partner.getTimeoutMs()))
                .map(response -> createSuccessResponse(partner, response, startTime))
                .onErrorResume(throwable ->
                        Mono.just(createErrorResponse(partner, throwable.getMessage(), startTime)))
                .toFuture();
    }

    private void configureHeaders(HttpHeaders headers, Partner partner) {
        headers.setContentType(MediaType.APPLICATION_JSON);

        switch (partner.getAuthenticationType().toUpperCase()) {
            case "API_KEY" -> headers.set("X-API-Key", partner.getApiKey());
            case "BEARER" -> headers.setBearerAuth(partner.getApiKey());
            case "BASIC" -> {
                String[] credentials = partner.getApiKey().split(":");
                if (credentials.length == 2) {
                    headers.setBasicAuth(credentials[0], credentials[1]);
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
                // Default to JSON
                return Map.of(
                        partner.getUidFieldName(), uid,
                        "timestamp", Instant.now().toString(),
                        "requestId", UUID.randomUUID().toString()
                );
        }
    }

    private PartnerResponse createSuccessResponse(Partner partner, Map<String, Object> response, long startTime) {
        long responseTime = System.currentTimeMillis() - startTime;

        // CRITICAL FIX: Use partner-specific configuration
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

        // Support multiple success patterns (comma-separated)
        String[] successPatterns = partner.getSuccessStatusPattern().split(",");
        for (String pattern : successPatterns) {
            if (pattern.trim().equalsIgnoreCase(actualStatus.trim())) {
                return true;
            }
        }
        return false;
    }

    // Enhanced field extraction for nested paths
    private String extractFieldValue(String path, Map<String, Object> response) {
        if (path == null || path.isEmpty()) {
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

            return current != null ? String.valueOf(current) : "N/A";
        } catch (Exception e) {
            log.warn("Failed to extract field '{}' from response: {}", path, e.getMessage());
            return "EXTRACTION_ERROR";
        }
    }

    private PartnerResponse createErrorResponse(Partner partner, String error, long startTime) {
        long responseTime = System.currentTimeMillis() - startTime;
        boolean timeout = error.toLowerCase().contains("timeout");

        log.warn("Error from partner {}: {}, time={}ms",
                partner.getId(), error, responseTime);

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