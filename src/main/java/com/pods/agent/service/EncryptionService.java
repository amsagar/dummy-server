package com.pods.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for sensitive values (API keys).
 *
 * Mirrors the nx-ai-agent Encryption service pattern.
 *
 * Stored format: base64(iv):base64(authTag):base64(ciphertext)
 *
 * Master key is read from environment variable PODS_ENCRYPTION_KEY (32-byte hex).
 * If not set, encrypt/decrypt will throw — store no keys without a master key.
 */
@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH  = 12; // bytes
    private static final int GCM_TAG_LENGTH = 128; // bits

    private final byte[] masterKey;

    public EncryptionService(@Value("${pods.encryption.key:}") String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            log.warn("[EncryptionService] PODS_ENCRYPTION_KEY not set — API key storage will be unavailable");
            this.masterKey = null;
        } else {
            this.masterKey = hexToBytes(hexKey.trim());
            if (this.masterKey.length != 32) {
                throw new IllegalArgumentException(
                        "pods.encryption.key must be a 64-character hex string (32 bytes / AES-256). " +
                        "Generate one with: openssl rand -hex 32");
            }
            log.info("[EncryptionService] Ready (AES-256-GCM)");
        }
    }

    public boolean isConfigured() {
        return masterKey != null;
    }

    /**
     * Encrypt a plaintext value.
     * @return base64(iv):base64(tag):base64(ciphertext)
     */
    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherWithTag = cipher.doFinal(plaintext.getBytes());

            // GCM appends the auth tag at the end — split it off
            int cipherLen = cipherWithTag.length - (GCM_TAG_LENGTH / 8);
            byte[] ciphertext = new byte[cipherLen];
            byte[] tag = new byte[GCM_TAG_LENGTH / 8];
            System.arraycopy(cipherWithTag, 0, ciphertext, 0, cipherLen);
            System.arraycopy(cipherWithTag, cipherLen, tag, 0, tag.length);

            return b64(iv) + ":" + b64(tag) + ":" + b64(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value encrypted by {@link #encrypt}.
     */
    public String decrypt(String encoded) {
        requireConfigured();
        try {
            String[] parts = encoded.split(":");
            if (parts.length != 3) throw new IllegalArgumentException("Invalid encrypted format");
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] tag        = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

            // Re-combine ciphertext + tag for GCM (Java expects them joined)
            byte[] cipherWithTag = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, cipherWithTag, 0, ciphertext.length);
            System.arraycopy(tag, 0, cipherWithTag, ciphertext.length, tag.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherWithTag));
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SecretKey secretKey() {
        return new SecretKeySpec(masterKey, "AES");
    }

    private void requireConfigured() {
        if (masterKey == null) {
            throw new IllegalStateException(
                    "Encryption not configured — set pods.encryption.key (or PODS_ENCRYPTION_KEY env var). " +
                    "Generate a key with: openssl rand -hex 32");
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
