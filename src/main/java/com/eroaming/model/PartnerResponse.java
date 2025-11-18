package com.eroaming.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PartnerResponse {
    private String partnerId;
    private boolean success;
    private String status;
    private String message;
    private long responseTimeMs;
    private boolean timeout;
}