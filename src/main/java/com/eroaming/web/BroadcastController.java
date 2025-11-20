package com.eroaming.web;

import com.eroaming.model.BroadcastRequest;
import com.eroaming.model.BroadcastResponse;
import com.eroaming.service.BroadcastOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/v1/broadcast")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastOrchestrator broadcastOrchestrator;

    @PostMapping("/start-charging")
    public CompletableFuture<ResponseEntity<BroadcastResponse>> startCharging(
            @Valid @RequestBody BroadcastRequest request) {

        log.info("Received start-charging request for UID: {}", request.getUid());

        return broadcastOrchestrator.broadcastStartCharging(request)
                .thenApply(response -> {
                    if (response.isSuccess()) {
                        log.info("Broadcast successful for UID: {} - Partner: {}",
                                request.getUid(), response.getRespondingPartner());
                        return ResponseEntity.ok(response);
                    } else {
                        log.warn("Broadcast failed for UID: {} - Reason: {}",
                                request.getUid(), response.getMessage());
                        return ResponseEntity.badRequest().body(response);
                    }
                })
                .exceptionally(throwable -> {
                    log.error("Broadcast error for UID: {}", request.getUid(), throwable);
                    return ResponseEntity.internalServerError()
                            .body(BroadcastResponse.builder()
                                    .success(false)
                                    .message("Internal server error: " + throwable.getMessage())
                                    .build());
                });
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Broadcast Service is healthy");
    }
}