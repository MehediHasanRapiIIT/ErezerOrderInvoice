package com.erezer.admin.courier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for stored credentials (courier and Firebase). Output is base64(iv || ciphertext+tag).
 * Losing or changing {@code COURIER_ENCRYPTION_KEY} makes stored keys unreadable (re-enter them in Settings).
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(@Value("${app.courier.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("COURIER_ENCRYPTION_KEY must be set (openssl rand -base64 32)");
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("COURIER_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt secret", ex);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Stored credentials cannot be decrypted; re-enter them in Settings", ex);
        }
    }
}
