package com.gatekeeper.qaplatform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests from the login cookie.
 *
 * <p>This is deliberately not Spring's {@code oauth2ResourceServer}. That support switches off CSRF
 * for any request where its token resolver finds a token, which is safe for an Authorization header
 * but would remove CSRF protection from this cookie.
 */
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    private final AuthCookies cookies;
    private final JwtTokenService tokens;
    private final AppUserRepository users;

    public JwtCookieAuthenticationFilter(
            AuthCookies cookies, JwtTokenService tokens, AppUserRepository users) {
        this.cookies = cookies;
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        cookies.read(request)
                .flatMap(tokens::parseUserId)
                .flatMap(users::findById)
                .filter(AppUser::isEnabled)
                .ifPresent(JwtCookieAuthenticationFilter::authenticate);
        chain.doFilter(request, response);
    }

    private static void authenticate(AppUser user) {
        var principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        SecurityContextHolder.setContext(context);
    }
}
