package com.eroaming.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BroadcastResponse {
    private boolean success;
    private String message;
    private String respondingPartner;
    private List<PartnerResponse> partnerResponses;
    private long totalTimeMs;
}