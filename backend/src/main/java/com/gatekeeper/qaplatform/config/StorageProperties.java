package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param endpoint address of an S3-compatible store such as MinIO; empty means AWS S3
 * @param createBucket creates the bucket on first use; for local development only
 */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(
        String endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        boolean createBucket) {

    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + ", accessKey=***, secretKey=***]";
    }
}
