package com.eroaming.partnerconfiguration;

import com.eroaming.config.CryptoConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "partner_configurations")
@EntityListeners(AuditingEntityListener.class)
public class PartnerConfigEntity {

    @Id
    private String partnerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private String startChargingEndpoint;

    @Builder.Default
    private String requestFormat = "JSON";

    @Builder.Default
    private String successStatusPattern = "success";

    @Builder.Default
    private String uidFieldName = "uid";

    @Builder.Default
    private String responseStatusPath = "status";

    @Builder.Default
    private String responseMessagePath = "message";

    @Column(nullable = false)
    private String authenticationType;

    @Convert(converter = CryptoConverter.class)
    private String apiKey;

    private Integer timeoutMs = 5000;
    private Boolean enabled = true;
    private String status = "ACTIVE";
    private String httpMethod = "POST";

    @ElementCollection
    @CollectionTable(name = "partner_custom_headers")
    @MapKeyColumn(name = "header_name")
    @Column(name = "header_value")
    private Map<String, String> customHeaders = new HashMap<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}