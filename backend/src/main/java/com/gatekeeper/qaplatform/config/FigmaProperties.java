package com.gatekeeper.qaplatform.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.figma")
public record FigmaProperties(@NotBlank String apiUrl) {}
