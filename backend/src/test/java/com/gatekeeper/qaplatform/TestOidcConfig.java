package com.gatekeeper.qaplatform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Replaces issuer discovery, which would need a live identity provider at startup. A plain
 * {@code @Configuration} is picked up by component scanning in every Spring test.
 */
@Configuration
public class TestOidcConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("oidc")
                .clientId("test-client")
                .clientSecret("test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://gatekeeper.example.test/login/oauth2/code/oidc")
                .scope("openid", "profile", "email")
                .authorizationUri("https://idp.example.test/authorize")
                .tokenUri("https://idp.example.test/token")
                .jwkSetUri("https://idp.example.test/jwks")
                .issuerUri("https://idp.example.test")
                .userNameAttributeName("sub")
                .build());
    }
}
