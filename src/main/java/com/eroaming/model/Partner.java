package com.eroaming.model;

import jakarta.persistence.Column;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class Partner {
    private String id;
    @Column(unique = true)
    private String name;
    private String baseUrl;
    private String startChargingEndpoint;
    private String authenticationType;
    private String apiKey;

    @Builder.Default private int timeoutMs = 5000;
    @Builder.Default private String httpMethod = "POST";

    // NEW: Dynamic configuration
    @Builder.Default private String requestFormat = "JSON";
    @Builder.Default private String successStatusPattern = "success";
    @Builder.Default private String uidFieldName = "uid";
    @Builder.Default private String responseStatusPath = "status";
    @Builder.Default private String responseMessagePath = "message";

    private Map<String, String> customHeaders;
}