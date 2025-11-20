package com.eroaming.service;

import com.eroaming.model.Partner;
import com.eroaming.partnerconfiguration.PartnerConfigEntity;
import com.eroaming.partnerconfiguration.PartnerConfigRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing partner configurations and providing cached access to active partners.
 *
 * <p>This service acts as the gateway to partner configuration data, providing:
 * <ul>
 *   <li><b>Cached Access:</b> In-memory cache of active partner configurations</li>
 *   <li><b>Dynamic Configuration:</b> Runtime updates to partner settings</li>
 *   <li><b>Data Integrity:</b> Validation and encryption of sensitive data</li>
 *   <li><b>Performance Optimization:</b> Efficient loading and refresh mechanisms</li>
 * </ul>
 *
 * <p><b>Cache Strategy:</b>
 * The service uses Caffeine cache with the following characteristics:
 * <ul>
 *   <li><b>Size:</b> Maximum 100 partners cached</li>
 *   <li><b>TTL:</b> 30-minute expiration after write</li>
 *   <li><b>Loading:</b> Automatic loading on cache miss</li>
 *   <li><b>Preloading:</b> All active partners loaded at startup</li>
 *   <li><b>Invalidation:</b> Immediate cache refresh on configuration changes</li>
 * </ul>
 *
 * <p><b>Configuration Management:</b>
 * Partners can be dynamically configured with:
 * <ul>
 *   <li>Base URLs and API endpoints</li>
 *   <li>Authentication methods and encrypted API keys</li>
 *   <li>Request/response format specifications</li>
 *   <li>Timeout values and custom headers</li>
 *   <li>Success pattern matching rules</li>
 *   <li>JSON field mapping paths</li>
 * </ul>
 *
 * <p><b>Security Features:</b>
 * <ul>
 *   <li>API keys encrypted using AES-GCM before database storage</li>
 *   <li>Encryption key sourced from environment variables</li>
 *   <li>Automatic decryption when retrieving from cache</li>
 * </ul>
 *
 * <p><b>Usage Patterns:</b>
 * Primarily used by BroadcastOrchestrator to retrieve active partners for broadcasting,
 * and by administrative functions for partner configuration management.
 *
 * @see PartnerConfigEntity
 * @see PartnerConfigRepository
 * @see LoadingCache
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final PartnerConfigRepository partnerRepository;
    private LoadingCache<String, Partner> cache;

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(100)
                .build(this::loadPartnerFromDb);
        preloadCache();
    }

    /**
     * Retrieves all active partners from the cache.
     *
     * @return A list of active partners.
     */
    public List<Partner> getActivePartners() {
        int cacheSize = cache.asMap().size();
        log.debug("Retrieving {} active partners from cache", cacheSize);
        return List.copyOf(cache.asMap().values());
    }

    /**
     * Retrieves a partner by ID, first checking the cache before querying the database.
     *
     * @param partnerId The ID of the partner to retrieve.
     * @return An Optional containing the Partner if found, or empty if not found.
     */
    public Optional<Partner> getPartner(String partnerId) {
        try {
            Partner partner = cache.get(partnerId);
            log.debug("Cache hit - Partner: {}", partnerId);
            return Optional.of(partner);
        } catch (Exception e) {
            log.debug("Cache miss - Partner: {}", partnerId);
            return Optional.empty();
        }
    }

    /**
     * Creates a new partner configuration and refreshes the cache.
     *
     * @param partner The PartnerConfigEntity to create.
     * @return The created PartnerConfigEntity.
     */
    @Transactional
    public PartnerConfigEntity createPartner(PartnerConfigEntity partner) {
        log.info("Creating new partner - ID: {}, Name: {}", partner.getPartnerId(), partner.getName());

        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshCache();

        log.info("Partner created successfully - ID: {}", saved.getPartnerId());
        return saved;
    }

    /**
     * Updates an existing partner configuration and refreshes the cache.
     *
     * @param partner The PartnerConfigEntity to update.
     * @return The updated PartnerConfigEntity.
     */
    @Transactional
    public PartnerConfigEntity updatePartner(PartnerConfigEntity partner) {
        log.info("Updating partner - ID: {}", partner.getPartnerId());

        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshPartner(partner.getPartnerId());

        log.info("Partner updated successfully - ID: {}", saved.getPartnerId());
        return saved;
    }

    /**
     * Disables a partner by setting its enabled status to false and refreshes the cache.
     *
     * @param partnerId The ID of the partner to disable.
     */
    @Transactional
    public void disablePartner(String partnerId) {
        log.info("Disabling partner - ID: {}", partnerId);

        partnerRepository.updateEnabledStatus(partnerId, false);
        refreshPartner(partnerId);

        log.info("Partner disabled successfully - ID: {}", partnerId);
    }

    /**
     * Refreshes the entire partner cache by invalidating all entries and preloading active partners.
     */
    public void refreshCache() {
        log.info("Refreshing entire partner cache");
        cache.invalidateAll();
        preloadCache();
        log.info("Partner cache refreshed");
    }

    /**
     * Preloads all active partners from the database into the cache.
     */
    private void preloadCache() {
        List<PartnerConfigEntity> entities = partnerRepository.findActivePartners();
        entities.forEach(entity -> {
            Partner partner = toPartner(entity);
            cache.put(entity.getPartnerId(), partner);
        });
        log.info("Preloaded {} partners into cache", entities.size());
    }

    /**
     * Loads a partner from the database by ID.
     *
     * @param partnerId The ID of the partner to load.
     * @return The Partner object.
     */
    private Partner loadPartnerFromDb(String partnerId) {
        return partnerRepository.findByPartnerIdAndEnabledTrue(partnerId)
                .map(this::toPartner)
                .orElseThrow(() -> {
                    log.warn("Partner not found in database - Partner: {}", partnerId);
                    return new RuntimeException("Partner not found: " + partnerId);
                });
    }

    /**
     * Refreshes a single partner in the cache by invalidating its entry.
     *
     * @param partnerId The ID of the partner to refresh.
     */
    public void refreshPartner(String partnerId) {
        log.debug("Refreshing single partner in cache - Partner: {}", partnerId);
        cache.invalidate(partnerId);
    }

    private Partner toPartner(PartnerConfigEntity entity) {
        return Partner.builder()
                .id(entity.getPartnerId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .startChargingEndpoint(entity.getStartChargingEndpoint())
                .authenticationType(entity.getAuthenticationType())
                .apiKey(entity.getApiKey())
                .timeoutMs(entity.getTimeoutMs())
                .httpMethod(entity.getHttpMethod())
                .uidFieldName(entity.getUidFieldName())
                .customHeaders(entity.getCustomHeaders())
                .requestFormat(entity.getRequestFormat())
                .successStatusPattern(entity.getSuccessStatusPattern())
                .responseStatusPath(entity.getResponseStatusPath())
                .responseMessagePath(entity.getResponseMessagePath())
                .build();
    }
}