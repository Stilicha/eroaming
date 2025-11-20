package com.eroaming.service;

import com.eroaming.model.BroadcastRequest;
import com.eroaming.model.BroadcastResponse;
import com.eroaming.model.Partner;
import com.eroaming.model.PartnerResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core service responsible for orchestrating the broadcast of start-charging requests
 * to all active partners in the eRoaming hub network.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Retrieves active partners from cache</li>
 *   <li>Sends concurrent requests to all partners</li>
 *   <li>Implements 5-second timeout with early termination on first success</li>
 *   <li>Collects and aggregates partner responses</li>
 *   <li>Provides comprehensive metrics and logging</li>
 * </ul>
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>Uses bounded thread pool to prevent resource exhaustion</li>
 *   <li>Implements early termination to minimize response time</li>
 *   <li>Caches partner configurations for optimal performance</li>
 * </ul>
 */
@Slf4j
@Service
public class BroadcastOrchestrator {

    private final PartnerService partnerService;
    private final PartnerHttpClient partnerHttpClient;
    private final MeterRegistry meterRegistry;

    private final ExecutorService executorService = new ThreadPoolExecutor(
            10,
            50,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final Counter broadcastSuccessCounter;
    private final Counter broadcastFailureCounter;
    private final Counter earlyTerminationCounter;
    private final Timer broadcastTimer;

    public BroadcastOrchestrator(PartnerService partnerService, PartnerHttpClient partnerHttpClient, MeterRegistry meterRegistry) {
        this.partnerService = partnerService;
        this.partnerHttpClient = partnerHttpClient;
        this.meterRegistry = meterRegistry;

        this.broadcastSuccessCounter = Counter.builder("broadcast.success")
                .description("Successful broadcasts")
                .register(meterRegistry);

        this.broadcastFailureCounter = Counter.builder("broadcast.failure")
                .description("Failed broadcasts")
                .register(meterRegistry);

        this.earlyTerminationCounter = Counter.builder("broadcast.early.termination")
                .description("Broadcasts that terminated early due to success")
                .register(meterRegistry);

        this.broadcastTimer = Timer.builder("broadcast.duration")
                .description("Broadcast request duration")
                .register(meterRegistry);
    }

    /**
     * Broadcasts start charging requests to all active partners with early termination on first success.
     *
     * @param request The broadcast request containing the UID.
     * @return A CompletableFuture of BroadcastResponse indicating the result of the broadcast.
     */
    public CompletableFuture<BroadcastResponse> broadcastStartCharging(BroadcastRequest request) {
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        List<Partner> activePartners = partnerService.getActivePartners();

        log.info("Starting broadcast to {} partners for UID: {}", activePartners.size(), request.getUid());

        if (activePartners.isEmpty()) {
            sample.stop(broadcastTimer);
            broadcastFailureCounter.increment();
            log.warn("No active partners available for broadcast - UID: {}", request.getUid());

            return CompletableFuture.completedFuture(
                    BroadcastResponse.builder()
                            .success(false)
                            .message("No active partners available")
                            .totalTimeMs(System.currentTimeMillis() - startTime)
                            .build()
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                BroadcastResponse response = executeBroadcastWithEarlyTermination(activePartners, request.getUid(), startTime);
                sample.stop(broadcastTimer);

                if (response.isSuccess()) {
                    broadcastSuccessCounter.increment();
                    log.info("Broadcast completed successfully - UID: {}, TotalTime: {}ms",
                            request.getUid(), response.getTotalTimeMs());
                } else {
                    broadcastFailureCounter.increment();
                    log.warn("Broadcast failed - UID: {}, TotalTime: {}ms",
                            request.getUid(), response.getTotalTimeMs());
                }

                return response;
            } catch (Exception e) {
                sample.stop(broadcastTimer);
                broadcastFailureCounter.increment();
                log.error("Broadcast execution error - UID: {}, Error: {}", request.getUid(), e.getMessage());
                throw e;
            }
        }, executorService);
    }

    /**
     * Gracefully shuts down the executor service on application termination.
     */
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Thread pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Executes the broadcast with early termination upon first successful response.
     *
     * @param partners  List of active partners to send requests to.
     * @param uid       The unique identifier for the charging session.
     * @param startTime The start time of the broadcast for timing calculations.
     * @return A BroadcastResponse summarizing the results of the broadcast.
     */
    private BroadcastResponse executeBroadcastWithEarlyTermination(List<Partner> partners, String uid, long startTime) {
        AtomicReference<PartnerResponse> firstSuccess = new AtomicReference<>();
        List<PartnerResponse> collectedResponses = new ArrayList<>();
        List<CompletableFuture<PartnerResponse>> futures = new ArrayList<>();

        ExecutorCompletionService<PartnerResponse> completionService =
                new ExecutorCompletionService<>(executorService);

        for (Partner partner : partners) {
            CompletableFuture<PartnerResponse> future = partnerHttpClient
                    .sendStartChargingRequest(partner, uid);

            futures.add(future);
            completionService.submit(future::get);
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
                    break;
                }

                try {
                    PartnerResponse response = completedFuture.get();
                    collectedResponses.add(response);
                    receivedResponses++;

                    if (response.isSuccess() && firstSuccess.compareAndSet(null, response)) {
                        earlyTerminationCounter.increment();
                        log.info("Early termination - First success from partner: {}, UID: {}",
                                response.getPartnerId(), uid);
                        break;
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

    /**
     * Builds the final BroadcastResponse based on collected partner responses.
     *
     * @param responses       List of PartnerResponses received.
     * @param successResponse The first successful PartnerResponse, if any.
     * @param uid             The unique identifier for the charging session.
     * @param totalTime       The total time taken for the broadcast.
     * @return A BroadcastResponse summarizing the results.
     */
    private BroadcastResponse buildBroadcastResponse(
            List<PartnerResponse> responses,
            PartnerResponse successResponse,
            String uid, long totalTime) {

        long successCount = responses.stream().filter(PartnerResponse::isSuccess).count();
        long timeoutCount = responses.stream().filter(PartnerResponse::isTimeout).count();
        long errorCount = responses.stream().filter(r -> !r.isSuccess() && !r.isTimeout()).count();

        log.info("Broadcast summary - UID: {}, Partners: {}/{}, Success: {}, Timeouts: {}, Errors: {}, TotalTime: {}ms",
                uid, responses.size(), responses.size() + (successResponse != null ? 0 : 0),
                successCount, timeoutCount, errorCount, totalTime);

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