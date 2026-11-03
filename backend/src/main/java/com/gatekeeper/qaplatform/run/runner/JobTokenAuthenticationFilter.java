package com.gatekeeper.qaplatform.run.runner;

import com.gatekeeper.qaplatform.run.TestRunRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates sandboxes. The run is checked on each request, so a token stops working as soon as
 * its run ends, even before it expires. Tokens come in headers only, so CSRF doesn't apply here.
 */
public class JobTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** Claude Code sends its credential here. */
    private static final String API_KEY_HEADER = "x-api-key";

    private final JobTokenService tokens;
    private final TestRunRepository runs;
    private final Clock clock;

    public JobTokenAuthenticationFilter(JobTokenService tokens, TestRunRepository runs, Clock clock) {
        this.tokens = tokens;
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        credential(request)
                .flatMap(tokens::parseRunId)
                .flatMap(runs::findById)
                .filter(run -> run.acceptsRunner(clock.instant()))
                .ifPresent(run -> authenticate(new RunnerPrincipal(run.getId())));
        chain.doFilter(request, response);
    }

    private static Optional<String> credential(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            return Optional.of(header.substring(BEARER.length()));
        }
        return Optional.ofNullable(request.getHeader(API_KEY_HEADER));
    }

    private static void authenticate(RunnerPrincipal principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_RUNNER"))));
        SecurityContextHolder.setContext(context);
    }
}
