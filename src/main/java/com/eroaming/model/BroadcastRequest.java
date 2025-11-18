package com.eroaming.model;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class BroadcastRequest {
    @NotBlank(message = "UID is required")
    private String uid;
}