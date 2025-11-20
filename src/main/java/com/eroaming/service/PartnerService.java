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

    public List<Partner> getActivePartners() {
        int cacheSize = cache.asMap().size();
        log.debug("Retrieving {} active partners from cache", cacheSize);
        return List.copyOf(cache.asMap().values());
    }

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

    @Transactional
    public PartnerConfigEntity createPartner(PartnerConfigEntity partner) {
        log.info("Creating new partner - ID: {}, Name: {}", partner.getPartnerId(), partner.getName());

        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshCache();

        log.info("Partner created successfully - ID: {}", saved.getPartnerId());
        return saved;
    }

    @Transactional
    public PartnerConfigEntity updatePartner(PartnerConfigEntity partner) {
        log.info("Updating partner - ID: {}", partner.getPartnerId());

        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshPartner(partner.getPartnerId());

        log.info("Partner updated successfully - ID: {}", saved.getPartnerId());
        return saved;
    }

    @Transactional
    public void disablePartner(String partnerId) {
        log.info("Disabling partner - ID: {}", partnerId);

        partnerRepository.updateEnabledStatus(partnerId, false);
        refreshPartner(partnerId);

        log.info("Partner disabled successfully - ID: {}", partnerId);
    }

    public void refreshCache() {
        log.info("Refreshing entire partner cache");
        cache.invalidateAll();
        preloadCache();
        log.info("Partner cache refreshed");
    }

    private void preloadCache() {
        List<PartnerConfigEntity> entities = partnerRepository.findActivePartners();
        entities.forEach(entity -> {
            Partner partner = toPartner(entity);
            cache.put(entity.getPartnerId(), partner);
        });
        log.info("Preloaded {} partners into cache", entities.size());
    }

    private Partner loadPartnerFromDb(String partnerId) {
        return partnerRepository.findByPartnerIdAndEnabledTrue(partnerId)
                .map(this::toPartner)
                .orElseThrow(() -> {
                    log.warn("Partner not found in database - Partner: {}", partnerId);
                    return new RuntimeException("Partner not found: " + partnerId);
                });
    }

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