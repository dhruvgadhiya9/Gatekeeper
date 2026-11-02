package com.gatekeeper.qaplatform.config;

import com.gatekeeper.qaplatform.audit.AuditService;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.AuthCookies;
import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.auth.JwtCookieAuthenticationFilter;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.OidcLoginFailureHandler;
import com.gatekeeper.qaplatform.auth.OidcLoginSuccessHandler;
import com.gatekeeper.qaplatform.common.error.ProblemResponseWriter;
import com.gatekeeper.qaplatform.common.ratelimit.FixedWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authentication works in two stages:
 *
 * <ol>
 *   <li>The backend runs the OIDC authorization code flow as a confidential client. An HTTP session
 *       exists only to hold the login state during that redirect.
 *   <li>On success the session ends and the backend sets its own signed JWT in an HttpOnly cookie.
 *       Every later request is stateless.
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthProperties properties,
            AuthCookies authCookies,
            JwtTokenService tokens,
            AppUserRepository users,
            OidcLoginSuccessHandler loginSuccessHandler,
            OidcLoginFailureHandler loginFailureHandler,
            ProblemResponseWriter problems,
            AuditService audit,
            RateLimitProperties rateLimits,
            Clock clock)
            throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(properties.cookieSecure()).sameSite("Lax"));

        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        // The token is only ever sent in a cookie and header, never in a page, so the
                        // BREACH masking of the default handler would add nothing.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context ->
                        context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .oauth2Login(login -> login
                        .loginPage("/login")
                        // Keep the provider's tokens in the session that is about to end. The default
                        // holds them in memory indefinitely, and Gatekeeper never uses them.
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .successHandler(loginSuccessHandler)
                        .failureHandler(loginFailureHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            authCookies.clear(response);
                            if (authentication != null
                                    && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                                audit.record(user.email(), "LOGOUT", user.id().toString(), Map.of("outcome", "SUCCESS"));
                            }
                        })
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(response, HttpStatus.UNAUTHORIZED, "Sign in to continue."))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(response, HttpStatus.FORBIDDEN, "You do not have permission to do this.")))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // Runs before logout so that the logout handlers can see who is signing out.
                .addFilterBefore(new JwtCookieAuthenticationFilter(authCookies, tokens, users), LogoutFilter.class)
                .addFilterAfter(
                        new RateLimitFilter(new FixedWindowRateLimiter(clock, Duration.ofMinutes(1)), rateLimits, problems),
                        JwtCookieAuthenticationFilter.class);
        return http.build();
    }

    /** The SPA is served from the same origin as the API, so only that one origin is allowed. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.publicUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
