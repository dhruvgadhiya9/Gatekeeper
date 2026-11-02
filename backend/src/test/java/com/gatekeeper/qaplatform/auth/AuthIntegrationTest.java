package com.gatekeeper.qaplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AuthIntegrationTest extends IntegrationTestBase {

    private static final String PUBLIC_URL = "https://gatekeeper.example.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private OidcLoginSuccessHandler loginSuccessHandler;

    @Test
    void healthIsPublicAndApiIsNot() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void apiDocsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void validCookieAuthenticates() throws Exception {
        AppUser user = saveUser(Role.VIEWER);

        mockMvc.perform(get("/api/me").cookie(tokenCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.issuer").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist());
    }

    @Test
    void invalidCookieIsRejected() throws Exception {
        mockMvc.perform(get("/api/me").cookie(new Cookie(AuthCookies.TOKEN_COOKIE, "forged")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForUnknownUserIsRejected() throws Exception {
        Cookie cookie = new Cookie(AuthCookies.TOKEN_COOKIE, tokens.issue(UUID.randomUUID()));

        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingUserTakesEffectImmediately() throws Exception {
        AppUser user = saveUser(Role.VIEWER);
        Cookie cookie = tokenCookie(user);
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());

        user.setEnabled(false);
        users.save(user);

        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    /** Guards against the login cookie silently bypassing CSRF. See JwtCookieAuthenticationFilter. */
    @Test
    void stateChangeWithValidCookieButNoCsrfTokenIsForbidden() throws Exception {
        AppUser admin = saveUser(Role.ADMIN);
        AppUser target = saveUser(Role.VIEWER);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(tokenCookie(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"enabled\":true}"))
                .andExpect(status().isForbidden());

        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.VIEWER);
    }

    @Test
    void wrongCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(tokenCookie(saveUser(Role.VIEWER)), new Cookie("XSRF-TOKEN", "right"))
                        .header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfCookieIsReadableByTheSpa() throws Exception {
        var response = mockMvc.perform(get("/api/me").cookie(tokenCookie(saveUser(Role.VIEWER))))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn()
                .getResponse();

        assertThat(response.getCookie("XSRF-TOKEN").isHttpOnly()).isFalse();
    }

    @Test
    void logoutClearsTheTokenCookie() throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/logout").cookie(tokenCookie(saveUser(Role.VIEWER)))))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.TOKEN_COOKIE, 0));
    }

    @Test
    void viewerAndQaLeadCannotManageUsers() throws Exception {
        for (Role role : List.of(Role.VIEWER, Role.QA_LEAD)) {
            Cookie cookie = tokenCookie(saveUser(role));
            mockMvc.perform(get("/api/users").cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
            mockMvc.perform(updateUser(UUID.randomUUID(), cookie, "{\"role\":\"ADMIN\",\"enabled\":true}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void adminCanListAndUpdateUsers() throws Exception {
        Cookie admin = tokenCookie(saveUser(Role.ADMIN));
        AppUser target = saveUser(Role.VIEWER);

        mockMvc.perform(get("/api/users").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem(target.getEmail())));

        mockMvc.perform(updateUser(target.getId(), admin, "{\"role\":\"QA_LEAD\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("QA_LEAD"));

        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.QA_LEAD);
    }

    @Test
    void adminCannotDemoteThemselves() throws Exception {
        AppUser admin = saveUser(Role.ADMIN);

        mockMvc.perform(updateUser(admin.getId(), tokenCookie(admin), "{\"role\":\"VIEWER\",\"enabled\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRoleIsRejected() throws Exception {
        Cookie admin = tokenCookie(saveUser(Role.ADMIN));
        UUID id = saveUser(Role.VIEWER).getId();

        mockMvc.perform(updateUser(id, admin, "{\"role\":\"SUPERUSER\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(updateUser(id, admin, "{\"enabled\":true}")).andExpect(status().isBadRequest());
    }

    @Test
    void updatingUnknownUserReturnsNotFound() throws Exception {
        mockMvc.perform(updateUser(
                        UUID.randomUUID(), tokenCookie(saveUser(Role.ADMIN)), "{\"role\":\"VIEWER\",\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginStartRedirectsToProviderWithStateAndNonce() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/oidc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("https://idp.example.test/authorize")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("state=")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("nonce=")));
    }

    @Test
    void corsAllowsOnlyThePublicOrigin() throws Exception {
        mockMvc.perform(options("/api/me")
                        .header(HttpHeaders.ORIGIN, PUBLIC_URL)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PUBLIC_URL));

        mockMvc.perform(options("/api/me")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void successfulLoginSetsSecureCookieAndEndsSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginSuccessHandler.onAuthenticationSuccess(
                request, response, oidcLogin(subject, "BOSS@example.test", true));

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(AuthCookies.TOKEN_COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax");
        AppUser saved = users.findByIssuerAndSubject("https://idp.example.test", subject).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getEmail()).isEqualTo("boss@example.test");
    }

    @Test
    void rejectedLoginSetsNoCookie() throws Exception {
        AppUser disabled = saveUser(Role.VIEWER);
        disabled.setEnabled(false);
        users.save(disabled);
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginSuccessHandler.onAuthenticationSuccess(
                new MockHttpServletRequest(), response, oidcLoginFor(disabled));

        assertThat(response.getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/login?error=account_disabled");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private MockHttpServletRequestBuilder updateUser(UUID id, Cookie auth, String body) {
        return withCsrf(put("/api/users/" + id).cookie(auth).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** The same double-submit a browser performs: the header repeats the cookie. */
    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        String token = UUID.randomUUID().toString();
        return request.cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token);
    }

    private Cookie tokenCookie(AppUser user) {
        return new Cookie(AuthCookies.TOKEN_COOKIE, tokens.issue(user.getId()));
    }

    private AppUser saveUser(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser(TestIssuer.VALUE, id, id + "@example.test");
        user.setRole(role);
        return users.save(user);
    }

    private static OAuth2AuthenticationToken oidcLoginFor(AppUser user) {
        return oidcLogin(user.getSubject(), user.getEmail(), true);
    }

    private static OAuth2AuthenticationToken oidcLogin(String subject, String email, boolean verified) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .issuer(TestIssuer.VALUE)
                .subject(subject)
                .claims(claims -> claims.putAll(Map.of("email", email, "email_verified", verified, "name", "A User")))
                .build();
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");
    }

    private static final class TestIssuer {
        static final String VALUE = "https://idp.example.test";
    }
}
