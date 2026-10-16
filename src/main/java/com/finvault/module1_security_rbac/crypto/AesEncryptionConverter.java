package com.finvault.module1_security_rbac.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * ==============================================================================
 * [Owner: Member 1 - Security & IAM Module]
 * AesEncryptionConverter: JPA AttributeConverter thực hiện mã hóa và giải mã tự động
 * ở cấp độ ứng dụng (Application-level Encryption) sử dụng thuật toán chuẩn AES-256-GCM.
 *
 * Tính năng bảo mật cốt lõi:
 * - Khóa bí mật 256-bit (32 bytes Hex) cấu hình tập trung trong application.yml
 * - Chế độ GCM (Galois/Counter Mode) cung cấp cả tính bảo mật (Confidentiality)
 *   lẫn tính toàn vẹn (Integrity Authentication) chống sửa đổi dữ liệu (Tampering)
 * - Mỗi bản ghi mã hóa sinh một IV (Initialization Vector) 12 bytes ngẫu nhiên mới hoàn toàn
 * - Ghép IV + CipherText + Authentication Tag và mã hóa Base64 trước khi ghi vào Database
 *
 * Điểm chờ nâng cấp (Extension Point):
 * - Tích hợp Cloud KMS (AWS KMS, Azure Key Vault, HashiCorp Vault) để tự động xoay vòng khóa (Key Rotation)
 * ==============================================================================
 */
@Converter
@Component
public class AesEncryptionConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(AesEncryptionConverter.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String DEFAULT_FALLBACK_KEY_HEX = "4f8b2c1e7a9d0f3b5e8a1c4d7f0b2e5a8d1c4f7b0e3a6c9d2f5b8e1a4d7c0f3e";

    private static byte[] staticKeyBytes;
    private static final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptionConverter() {
        if (staticKeyBytes == null) {
            staticKeyBytes = hexStringToByteArray(DEFAULT_FALLBACK_KEY_HEX);
        }
    }

    @Value("${finvault.crypto.aes-secret-key:4f8b2c1e7a9d0f3b5e8a1c4d7f0b2e5a8d1c4f7b0e3a6c9d2f5b8e1a4d7c0f3e}")
    public void setSecretKey(String hexKey) {
        if (hexKey != null && !hexKey.isBlank()) {
            staticKeyBytes = hexStringToByteArray(hexKey.trim());
            log.info("AesEncryptionConverter: Khóa mã hóa AES-256 đã được nạp thành công.");
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(getEffectiveKeyBytes(), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] messagePayload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, messagePayload, 0, iv.length);
            System.arraycopy(cipherText, 0, messagePayload, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(messagePayload);
        } catch (Exception e) {
            log.error("Lỗi nghiêm trọng khi thực hiện mã hóa AES-256-GCM cho trường dữ liệu nhạy cảm: {}", e.getMessage());
            throw new IllegalStateException("Không thể mã hóa dữ liệu nhạy cảm", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        try {
            byte[] messagePayload;
            try {
                messagePayload = Base64.getDecoder().decode(dbData);
            } catch (IllegalArgumentException notBase64) {
                // Hỗ trợ backward-compatibility nếu dữ liệu gốc là plain text
                return dbData;
            }

            if (messagePayload.length < GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
                return dbData;
            }

            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(messagePayload, 0, iv, 0, iv.length);

            int cipherTextLength = messagePayload.length - iv.length;
            byte[] cipherText = new byte[cipherTextLength];
            System.arraycopy(messagePayload, iv.length, cipherText, 0, cipherTextLength);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(getEffectiveKeyBytes(), "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Không thể giải mã trường dữ liệu (có thể là dữ liệu cũ chưa mã hóa): {}", e.getMessage());
            return dbData;
        }
    }

    private byte[] getEffectiveKeyBytes() {
        if (staticKeyBytes != null && staticKeyBytes.length == 32) {
            return staticKeyBytes;
        }
        return hexStringToByteArray(DEFAULT_FALLBACK_KEY_HEX);
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            s = "0" + s;
            len = s.length();
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
