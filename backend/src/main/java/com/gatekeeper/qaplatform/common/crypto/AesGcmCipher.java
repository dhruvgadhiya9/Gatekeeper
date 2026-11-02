package com.gatekeeper.qaplatform.common.crypto;

import com.gatekeeper.qaplatform.config.EncryptionProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for credentials stored at rest.
 *
 * <p>Output is Base64 of {@code formatVersion (1 byte) | IV (12 bytes) | ciphertext + tag}. The
 * version byte leaves room for a future key rotation.
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_PAYLOAD_BYTES = 1 + IV_BYTES + TAG_BITS / 8;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(EncryptionProperties properties) {
        this.key = decodeKey(properties.key());
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(1 + iv.length + ciphertext.length)
                    .put(FORMAT_VERSION)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("Stored value is not valid ciphertext", e);
        }
        if (payload.length < MIN_PAYLOAD_BYTES || payload[0] != FORMAT_VERSION) {
            throw new EncryptionException("Stored value is not valid ciphertext");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 1, IV_BYTES));
            byte[] plaintext = cipher.doFinal(payload, 1 + IV_BYTES, payload.length - 1 - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    private static SecretKey decodeKey(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            // Do not chain the cause: its message can quote the key.
            throw new EncryptionException("app.encryption.key must be valid Base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new EncryptionException("app.encryption.key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
