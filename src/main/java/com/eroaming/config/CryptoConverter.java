package com.eroaming.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * JPA AttributeConverter for encrypting/decrypting sensitive partner API keys in the database.
 *
 * <p><b>Security Implementation:</b>
 * <ul>
 *   <li>Uses AES-GCM encryption with 256-bit keys</li>
 *   <li>Generates random IV for each encryption</li>
 *   <li>Derives key from environment variable using SHA-256</li>
 *   <li>Stores IV + encrypted data as single Base64 string</li>
 * </ul>
 *
 * <p><b>Production Requirements:</b>
 * <ul>
 *   <li>ENCRYPTION_KEY environment variable must be set in production</li>
 *   <li>Key should be 32+ character random string</li>
 *   <li>Key rotation requires data re-encryption</li>
 * </ul>
 *
 * <p><b>Note:</b> For enterprise environments, consider using cloud KMS (AWS KMS, Azure Key Vault)
 * instead of environment-based key management.
 */
@Slf4j
@Converter
public class CryptoConverter implements AttributeConverter<String, String> {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private final SecretKeySpec keySpec;
    private final Cipher cipher;

    public CryptoConverter(@Value("${app.encryption.key}") String encryptionKey) {
        try {
            byte[] key = encryptionKey.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            key = sha.digest(key);
            this.keySpec = new SecretKeySpec(key, "AES");
            this.cipher = Cipher.getInstance(ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize crypto converter", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes());
            byte[] encrypted = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encrypted, 0, iv.length);
            System.arraycopy(cipherText, 0, encrypted, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData);
            byte[] iv = Arrays.copyOfRange(decoded, 0, 12);
            byte[] cipherText = Arrays.copyOfRange(decoded, 12, decoded.length);

            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText);
        } catch (Exception e) {
            log.error("Decryption failed for data: {}", dbData, e);
            throw new RuntimeException("Decryption failed", e);
        }
    }
}