package com.eroaming.service;

import com.eroaming.model.BroadcastRequest;
import com.eroaming.model.BroadcastResponse;
import com.eroaming.model.Partner;
import com.eroaming.model.PartnerResponse;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastOrchestrator {

    private final PartnerService partnerService;
    private final PartnerHttpClient partnerHttpClient;

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public CompletableFuture<BroadcastResponse> broadcastStartCharging(BroadcastRequest request) {
        long startTime = System.currentTimeMillis();
        List<Partner> activePartners = partnerService.getActivePartners();

        log.info("Starting broadcast to {} partners for UID: {}", activePartners.size(), request.getUid());

        if (activePartners.isEmpty()) {
            return CompletableFuture.completedFuture(
                    BroadcastResponse.builder()
                            .success(false)
                            .message("No active partners available")
                            .totalTimeMs(System.currentTimeMillis() - startTime)
                            .build()
            );
        }

        return CompletableFuture.supplyAsync(() ->
                        executeBroadcastWithEarlyTermination(activePartners, request.getUid(), startTime),
                executorService
        );
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private BroadcastResponse executeBroadcastWithEarlyTermination(List<Partner> partners, String uid, long startTime) {

        AtomicReference<PartnerResponse> firstSuccess = new AtomicReference<>();
        List<PartnerResponse> collectedResponses = new ArrayList<>();
        List<CompletableFuture<PartnerResponse>> futures = new ArrayList<>();

        // Create completion service for early termination
        ExecutorCompletionService<PartnerResponse> completionService =
                new ExecutorCompletionService<>(executorService);

        // Submit all requests
        for (Partner partner : partners) {
            CompletableFuture<PartnerResponse> future = partnerHttpClient
                    .sendStartChargingRequest(partner, uid);

            futures.add(future);

            // Also submit to completion service for ordered completion
            completionService.submit(() -> future.get());
        }

        int receivedResponses = 0;
        int totalPartners = partners.size();

        try {
            long timeoutTime = System.currentTimeMillis() + 5000;

            while (receivedResponses < totalPartners && System.currentTimeMillis() < timeoutTime) {
                Future<PartnerResponse> completedFuture = completionService.poll(
                        timeoutTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS
                );

                if (completedFuture == null) {
                    break; // Timeout reached
                }

                try {
                    PartnerResponse response = completedFuture.get();
                    collectedResponses.add(response);
                    receivedResponses++;

                    // Check if this is the first success
                    if (response.isSuccess() && firstSuccess.compareAndSet(null, response)) {
                        log.info("🎯 First success received from partner: {} - Stopping early",
                                response.getPartnerId());
                        break; // Exit loop on first success
                    }
                } catch (ExecutionException e) {
                    log.warn("Failed to get response from completed future", e);
                    receivedResponses++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Broadcast interrupted for UID: {}", uid);
        }

        futures.forEach(future -> future.cancel(true));

        long totalTime = System.currentTimeMillis() - startTime;
        return buildBroadcastResponse(collectedResponses, firstSuccess.get(), uid, totalTime);
    }

    private BroadcastResponse buildBroadcastResponse(
            List<PartnerResponse> responses,
            PartnerResponse successResponse,
            String uid, long totalTime) {

        // Calculate statistics
        long successCount = responses.stream().filter(PartnerResponse::isSuccess).count();
        long timeoutCount = responses.stream().filter(PartnerResponse::isTimeout).count();
        long errorCount = responses.stream().filter(r -> !r.isSuccess() && !r.isTimeout()).count();

        log.info("Broadcast summary for UID {}: total={}, success={}, timeouts={}, errors={}, totalTime={}ms",
                uid, responses.size(), successCount, timeoutCount, errorCount, totalTime);

        if (successResponse != null) {
            return BroadcastResponse.builder()
                    .success(true)
                    .message(String.format("Charging started successfully with partner %s",
                            successResponse.getPartnerId()))
                    .respondingPartner(successResponse.getPartnerId())
                    .partnerResponses(responses)
                    .totalTimeMs(totalTime)
                    .build();
        } else {
            String message = String.format(
                    "No partner accepted the charging request. %d partners responded (%d success, %d timeouts, %d errors)",
                    responses.size(), successCount, timeoutCount, errorCount
            );

            return BroadcastResponse.builder()
                    .success(false)
                    .message(message)
                    .partnerResponses(responses)
                    .totalTimeMs(totalTime)
                    .build();
        }
    }
}