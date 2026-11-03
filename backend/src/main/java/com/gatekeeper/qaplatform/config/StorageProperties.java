package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param endpoint address of an S3-compatible store such as MinIO; empty means AWS S3
 * @param sandboxEndpoint the address sandboxes use for pre-signed URLs; empty means {@code endpoint}
 * @param createBucket creates the bucket on first use; for local development only
 */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(
        String endpoint,
        String sandboxEndpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        boolean createBucket) {

    public String effectiveSandboxEndpoint() {
        return sandboxEndpoint != null && !sandboxEndpoint.isBlank() ? sandboxEndpoint : endpoint;
    }

    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + ", accessKey=***, secretKey=***]";
    }
}
