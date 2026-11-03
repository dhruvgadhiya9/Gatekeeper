package com.gatekeeper.qaplatform.integration.zoho;

import com.gatekeeper.qaplatform.auth.AuthenticatedUser;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.config.AuthProperties;
import com.gatekeeper.qaplatform.integration.zoho.ZohoConnectionService.Status;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.Portal;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.Project;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/integrations/zoho")
public class ZohoController {

    public record ConnectResponse(String authorizationUrl) {}

    private static final String STATE_COOKIE = "GK_ZOHO_STATE";
    private static final String COOKIE_PATH = "/api/integrations/zoho";
    private static final String ID = "[A-Za-z0-9_-]{1,64}";

    private final ZohoConnectionService connection;
    private final ZohoClient zoho;
    private final AuthProperties authProperties;
    private final SecureRandom random = new SecureRandom();

    public ZohoController(ZohoConnectionService connection, ZohoClient zoho, AuthProperties authProperties) {
        this.connection = connection;
        this.zoho = zoho;
        this.authProperties = authProperties;
    }

    @GetMapping("/status")
    public Status status() {
        return connection.status();
    }

    /**
     * A POST, so CSRF protection covers starting the flow. The state ties Zoho's redirect back to
     * this browser, so nobody can trick an admin into connecting the attacker's Zoho account.
     */
    @PostMapping("/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public ConnectResponse connect(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String url = connection.authorizationUrl(state);
        writeStateCookie(response, state, Duration.ofMinutes(10));
        return new ConnectResponse(url);
    }

    /** The browser lands here, so outcomes are redirects with fixed codes, not API errors. */
    @GetMapping("/callback")
    @PreAuthorize("hasRole('ADMIN')")
    public void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String error,
            @CookieValue(name = STATE_COOKIE, required = false) String expectedState,
            @AuthenticationPrincipal AuthenticatedUser admin,
            HttpServletResponse response)
            throws IOException {
        writeStateCookie(response, "", Duration.ZERO);
        response.sendRedirect(authProperties.publicUrl() + "/settings?zoho=" + finishConnect(code, state, location, error, expectedState, admin));
    }

    private String finishConnect(
            String code, String state, String location, String error, String expectedState, AuthenticatedUser admin) {
        if (!statesMatch(state, expectedState)) {
            return "invalid_state";
        }
        if (error != null || code == null) {
            return "denied";
        }
        try {
            connection.connect(code, location, admin.email());
            return "connected";
        } catch (ConflictException e) {
            return "wrong_data_center";
        } catch (RestClientException | com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException e) {
            return "failed";
        }
    }

    @DeleteMapping("/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void disconnect() {
        connection.disconnect();
    }

    @GetMapping("/portals")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<Portal> portals() {
        return zoho.listPortals();
    }

    @GetMapping("/portals/{portalId}/projects")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<Project> projects(@PathVariable @Pattern(regexp = ID) String portalId) {
        return zoho.listProjects(portalId);
    }

    @GetMapping("/portals/{portalId}/projects/{projectId}/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<User> users(
            @PathVariable @Pattern(regexp = ID) String portalId, @PathVariable @Pattern(regexp = ID) String projectId) {
        return zoho.listProjectUsers(portalId, projectId);
    }

    private static boolean statesMatch(String state, String expected) {
        return state != null
                && expected != null
                && !expected.isEmpty()
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void writeStateCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
