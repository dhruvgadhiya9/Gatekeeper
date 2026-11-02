package com.gatekeeper.qaplatform.config;

import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.common.error.ProblemResponseWriter;
import com.gatekeeper.qaplatform.common.ratelimit.FixedWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the login endpoints per client address and run starts per user. It must run after
 * authentication so it can identify the user.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String name, RequestMatcher matcher, int limit) {}

    private final FixedWindowRateLimiter limiter;
    private final ProblemResponseWriter problems;
    private final List<Rule> rules;

    RateLimitFilter(
            FixedWindowRateLimiter limiter, RateLimitProperties properties, ProblemResponseWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
        var paths = PathPatternRequestMatcher.withDefaults();
        this.rules = List.of(
                new Rule("login", paths.matcher("/oauth2/authorization/**"), properties.loginPerMinute()),
                new Rule("login", paths.matcher("/login/oauth2/code/**"), properties.loginPerMinute()),
                new Rule("run-start", paths.matcher(HttpMethod.POST, "/api/projects/*/runs"), properties.runStartPerMinute()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : rules) {
            if (rule.matcher().matches(request)
                    && !limiter.tryAcquire(rule.name() + ":" + clientKey(request), rule.limit())) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.windowSeconds()));
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in a minute.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String clientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id().toString();
        }
        return request.getRemoteAddr();
    }
}
