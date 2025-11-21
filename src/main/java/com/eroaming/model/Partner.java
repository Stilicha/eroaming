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
    private AuthenticationType authenticationType;
    private String apiKey;

    @Builder.Default private int timeoutMs = 5000;
    @Builder.Default private RequestType httpMethod = RequestType.POST;

    @Builder.Default private RequestFormat requestFormat = RequestFormat.JSON;
    @Builder.Default private String successStatusPattern = "success";
    @Builder.Default private String uidFieldName = "uid";
    @Builder.Default private String responseStatusPath = "status";
    @Builder.Default private String responseMessagePath = "message";

    private Map<String, String> customHeaders;
}