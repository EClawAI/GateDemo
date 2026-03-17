package com.clawai.gatedemo.gate.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption/decryption utility for message body.
 * Configurable via gate.security.encryption.enabled and gate.security.encryption.key.
 */
public class MessageEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(MessageEncryptor.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    /**
     * @param secretKeyString Base64 or raw string; will be hashed/padded to 16 bytes if needed
     */
    public MessageEncryptor(String secretKeyString) {
        byte[] keyBytes = deriveKey(secretKeyString);
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    private static byte[] deriveKey(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("Secret key cannot be null or empty");
        }
        byte[] input = s.getBytes(StandardCharsets.UTF_8);
        if (input.length == 16) {
            return input;
        }
        if (input.length < 16) {
            byte[] padded = new byte[16];
            System.arraycopy(input, 0, padded, 0, input.length);
            return padded;
        }
        byte[] truncated = new byte[16];
        System.arraycopy(input, 0, truncated, 0, 16);
        return truncated;
    }

    /**
     * Encrypt plainText with AES-GCM. Output format: Base64(IV || ciphertext || tag).
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] cipherText = cipher.doFinal(plainBytes);

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            logger.error("Encryption failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt Base64(IV || ciphertext || tag) to plain text.
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            if (decoded.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("Cipher text too short");
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            byte[] cipherBytes = new byte[buffer.remaining()];
            buffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Decryption failed: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
