package com.clawai.gatedemo.gate.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

public class MessageEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(MessageEncryptor.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private final SecretKeySpec secretKey;
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;

    public MessageEncryptor(byte[] key) {
        if (key.length != 16) {
            throw new IllegalArgumentException("Key must be 16 bytes");
        }
        this.secretKey = new SecretKeySpec(key, ALGORITHM);

        try {
            this.encryptCipher = Cipher.getInstance(TRANSFORMATION);
            this.encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey);

            this.decryptCipher = Cipher.getInstance(TRANSFORMATION);
            this.decryptCipher.init(Cipher.DECRYPT_MODE, secretKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize cipher", e);
        }
    }

    public static MessageEncryptor createWithRandomKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[16];
        random.nextBytes(key);
        return new MessageEncryptor(key);
    }

    public byte[] encrypt(byte[] data) {
        try {
            return encryptCipher.doFinal(data);
        } catch (Exception e) {
            logger.error("Encryption failed: {}", e.getMessage());
            return null;
        }
    }

    public byte[] decrypt(byte[] encryptedData) {
        try {
            return decryptCipher.doFinal(encryptedData);
        } catch (Exception e) {
            logger.error("Decryption failed: {}", e.getMessage());
            return null;
        }
    }

    public byte[] getKey() {
        return secretKey.getEncoded();
    }
}
