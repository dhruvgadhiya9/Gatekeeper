package com.gatekeeper.qaplatform.auth;

import com.gatekeeper.qaplatform.audit.AuditService;
import com.gatekeeper.qaplatform.config.AuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginFailureHandler.class);

    private final AuthProperties properties;
    private final AuditService audit;

    public OidcLoginFailureHandler(AuthProperties properties, AuditService audit) {
        this.properties = properties;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        // The OAuth error code, such as invalid_request, is a fixed value that says why login failed.
        String reason = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("OIDC login failed: {}", reason);
        audit.record("anonymous", "LOGIN", null, Map.of("outcome", "FAILURE", "reason", reason));
        response.sendRedirect(properties.publicUrl() + "/login?error=login_failed");
    }
}
