package com.gatekeeper.qaplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI qaPlatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Gatekeeper QA Platform API")
                .description("Internal API for registering projects, running AI QA agents and reviewing findings.")
                .version("v1"));
    }
}
