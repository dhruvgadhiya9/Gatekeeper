package com.gatekeeper.qaplatform.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.config.EncryptionProperties;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    private final AesGcmCipher cipher = cipherWithKeyByte((byte) 1);

    @Test
    void decryptReturnsOriginalText() {
        assertThat(cipher.decrypt(cipher.encrypt("pässwörd-✓"))).isEqualTo("pässwörd-✓");
    }

    @Test
    void sameTextEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("secret")).isNotEqualTo(cipher.encrypt("secret"));
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        byte[] payload = Base64.getDecoder().decode(cipher.encrypt("secret"));
        assertThat(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("secret");
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] payload = Base64.getDecoder().decode(cipher.encrypt("secret"));
        payload[payload.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void differentKeyCannotDecrypt() {
        String encrypted = cipher.encrypt("secret");

        assertThatThrownBy(() -> cipherWithKeyByte((byte) 2).decrypt(encrypted))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void malformedValuesAreRejected() {
        assertThatThrownBy(() -> cipher.decrypt("not base64!")).isInstanceOf(EncryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("AAAA")).isInstanceOf(EncryptionException.class);
    }

    @Test
    void keyMustBe32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmCipher(new EncryptionProperties(shortKey)))
                .isInstanceOf(EncryptionException.class)
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void propertiesNeverPrintTheKey() {
        assertThat(new EncryptionProperties("my-key").toString()).doesNotContain("my-key");
    }

    private static AesGcmCipher cipherWithKeyByte(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return new AesGcmCipher(new EncryptionProperties(Base64.getEncoder().encodeToString(key)));
    }
}
