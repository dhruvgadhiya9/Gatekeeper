package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param key Base64-encoded 256-bit AES key from the environment or a KMS. */
@Validated
@ConfigurationProperties("app.encryption")
public record EncryptionProperties(@NotBlank String key) {

    @Override
    public String toString() {
        return "EncryptionProperties[key=***]";
    }
}
