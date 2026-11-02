package com.gatekeeper.qaplatform.auth;

import com.gatekeeper.qaplatform.config.AuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Swaps a completed OIDC login for Gatekeeper's own token, then ends the temporary session. */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtTokenService tokens;
    private final AuthCookies cookies;
    private final AuthProperties properties;

    public OidcLoginSuccessHandler(
            UserService userService, JwtTokenService tokens, AuthCookies cookies, AuthProperties properties) {
        this.userService = userService;
        this.tokens = tokens;
        this.cookies = cookies;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        endTemporarySession(request);
        try {
            AppUser user = userService.recordLogin(toIdentity((OidcUser) authentication.getPrincipal()));
            cookies.write(response, tokens.issue(user.getId()));
            response.sendRedirect(properties.publicUrl() + "/");
        } catch (LoginRejectedException e) {
            response.sendRedirect(properties.publicUrl() + "/login?error=" + e.getMessage());
        }
    }

    private static OidcIdentity toIdentity(OidcUser oidcUser) {
        return new OidcIdentity(
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                oidcUser.getFullName());
    }

    private static void endTemporarySession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
