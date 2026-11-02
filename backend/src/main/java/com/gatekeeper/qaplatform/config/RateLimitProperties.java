package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param loginPerMinute login requests allowed per client address. One sign-in makes two requests.
 * @param runStartPerMinute run starts allowed per user
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(@Positive int loginPerMinute, @Positive int runStartPerMinute) {}
