package com.gatekeeper.qaplatform.auth;

import com.gatekeeper.qaplatform.config.AuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginFailureHandler.class);

    private final AuthProperties properties;

    public OidcLoginFailureHandler(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        log.warn("OIDC login failed: {}", exception.getClass().getSimpleName());
        response.sendRedirect(properties.publicUrl() + "/login?error=login_failed");
    }
}
