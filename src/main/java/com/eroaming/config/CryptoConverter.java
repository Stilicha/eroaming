package com.eroaming.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;

@Converter
@RequiredArgsConstructor
public class CryptoConverter implements AttributeConverter<String, String> {

    // For now, use simple Base64 encoding
    // In production, replace with proper encryption service
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        // Simple encoding for demo - replace with real encryption
        return java.util.Base64.getEncoder().encodeToString(attribute.getBytes());
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        // Simple decoding for demo - replace with real decryption
        return new String(java.util.Base64.getDecoder().decode(dbData));
    }
}
