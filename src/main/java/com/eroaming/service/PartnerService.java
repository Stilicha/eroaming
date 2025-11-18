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

    private final LoadingCache<String, Partner> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(100) // Can store up to 100 partners
            .build(this::loadPartnerFromDb);

    @PostConstruct
    public void init() {
        preloadCache();
    }

    public List<Partner> getActivePartners() {
        // Get all cached values (all partners in cache)
        return List.copyOf(cache.asMap().values());
    }

    public Optional<Partner> getPartner(String partnerId) {
        try {
            return Optional.of(cache.get(partnerId));
        } catch (Exception e) {
            return Optional.empty(); // Partner not found
        }
    }

    @Transactional
    public PartnerConfigEntity createPartner(PartnerConfigEntity partner) {
        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshCache();
        return saved;
    }

    @Transactional
    public PartnerConfigEntity updatePartner(PartnerConfigEntity partner) {
        PartnerConfigEntity saved = partnerRepository.save(partner);
        refreshCache();
        return saved;
    }

    @Transactional
    public void disablePartner(String partnerId) {
        partnerRepository.updateEnabledStatus(partnerId, false);
        refreshCache();
    }

    public void refreshCache() {
        cache.invalidateAll();
        preloadCache();
        log.info("Partners cache refreshed");
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
                .orElseThrow(() -> new RuntimeException("Partner not found: " + partnerId));
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