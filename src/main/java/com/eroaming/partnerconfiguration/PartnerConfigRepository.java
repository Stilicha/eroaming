package com.eroaming.partnerconfiguration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerConfigRepository extends JpaRepository<PartnerConfigEntity, String> {

    List<PartnerConfigEntity> findByEnabledTrueAndStatus(String status);

    @Modifying
    @Query("UPDATE PartnerConfigEntity p SET p.enabled = :enabled WHERE p.partnerId = :partnerId")
    void updateEnabledStatus(@Param("partnerId") String partnerId, @Param("enabled") boolean enabled);

    default List<PartnerConfigEntity> findActivePartners() {
        return findByEnabledTrueAndStatus("ACTIVE");
    }

    Optional<PartnerConfigEntity> findByPartnerIdAndEnabledTrue(String partnerId);
}