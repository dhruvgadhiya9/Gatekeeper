package com.gatekeeper.qaplatform.integration.zoho;

import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.CLIENT_SECRET;
import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.REFRESH_TOKEN;
import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.ZOHO;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ZohoConnectionIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/integrations/zoho";
    private static final String SETTINGS = "https://gatekeeper.example.test/settings?zoho=";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private ZohoConnectionRepository connections;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    static void zoho(DynamicPropertyRegistry registry) {
        ZohoTestSupport.register(registry);
    }

    @BeforeEach
    void reset() {
        ZOHO.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        connections.deleteAll();
        ZohoTestSupport.stubTokens();
    }

    @Test
    void connectBuildsAnOfflineConsentRequestWithAllScopes() throws Exception {
        MvcResult result = connect(login(Role.ADMIN));

        String url = objectMapper.readTree(result.getResponse().getContentAsString()).get("authorizationUrl").asText();
        assertThat(url)
                .startsWith(ZOHO.baseUrl() + "/oauth/v2/auth")
                .contains("access_type=offline", "prompt=consent", "response_type=code", "state=")
                .contains("ZohoProjects.bugs.CREATE", "WorkDrive.files.ALL")
                .contains("gatekeeper.example.test")
                .doesNotContain(CLIENT_SECRET);
        Cookie state = result.getResponse().getCookie("GK_ZOHO_STATE");
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(url).contains("state=" + state.getValue());
    }

    @Test
    void callbackStoresOnlyAnEncryptedRefreshToken() throws Exception {
        Cookie admin = login(Role.ADMIN);
        Cookie state = connect(admin).getResponse().getCookie("GK_ZOHO_STATE");

        callback(admin, state, "code=abc&state=" + state.getValue() + "&location=in")
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "connected"));

        ZOHO.verify(postRequestedFor(urlEqualTo("/oauth/v2/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=abc"))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET)));
        assertThat(jdbc.queryForObject("select encrypted_refresh_token from zoho_connection", String.class))
                .doesNotContain(REFRESH_TOKEN);
        assertThat(connections.findFirstByOrderByCreatedAtDesc().orElseThrow().getRefreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(auditEvents.findAll()).anyMatch(event -> "ZOHO_CONNECT".equals(event.getAction())
                && !event.getDetailsJson().contains(REFRESH_TOKEN));
    }

    @Test
    void statusNeverExposesTokens() throws Exception {
        connections.save(new ZohoConnection(REFRESH_TOKEN, "admin@example.test"));

        String body = mockMvc.perform(get(BASE + "/status").cookie(login(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.connectedBy").value("admin@example.test"))
                .andExpect(jsonPath("$.dataCenter").value("in"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(REFRESH_TOKEN, CLIENT_SECRET);
    }

    @Test
    void forgedOrMissingStateIsRefusedAndZohoIsNeverCalled() throws Exception {
        Cookie admin = login(Role.ADMIN);
        Cookie state = connect(admin).getResponse().getCookie("GK_ZOHO_STATE");

        callback(admin, state, "code=attacker&state=forged")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "invalid_state"));
        callback(admin, null, "code=attacker&state=" + state.getValue())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "invalid_state"));

        ZOHO.verify(0, postRequestedFor(urlEqualTo("/oauth/v2/token")));
        assertThat(connections.count()).isZero();
    }

    @Test
    void wrongDataCenterIsRefusedAndTheAccountsServerParameterIsIgnored() throws Exception {
        Cookie admin = login(Role.ADMIN);
        Cookie state = connect(admin).getResponse().getCookie("GK_ZOHO_STATE");

        callback(admin, state, "code=abc&state=" + state.getValue() + "&location=eu&accounts-server=https://evil.test")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "wrong_data_center"));

        ZOHO.verify(0, postRequestedFor(urlEqualTo("/oauth/v2/token")));
    }

    @Test
    void userDecliningAtZohoIsReported() throws Exception {
        Cookie admin = login(Role.ADMIN);
        Cookie state = connect(admin).getResponse().getCookie("GK_ZOHO_STATE");

        callback(admin, state, "error=access_denied&state=" + state.getValue())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "denied"));
    }

    @Test
    void zohoErrorsReturnedWithStatus200AreTreatedAsFailures() throws Exception {
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token")).willReturn(okJson("{\"error\":\"invalid_code\"}")));
        Cookie admin = login(Role.ADMIN);
        Cookie state = connect(admin).getResponse().getCookie("GK_ZOHO_STATE");

        callback(admin, state, "code=old&state=" + state.getValue())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(SETTINGS + "failed"));

        assertThat(connections.count()).isZero();
    }

    @Test
    void onlyAdminsCanConnectOrDisconnect() throws Exception {
        for (Role role : new Role[] {Role.QA_LEAD, Role.VIEWER}) {
            mockMvc.perform(withCsrf(MockMvcRequestBuilders.post(BASE + "/connect")).cookie(login(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withCsrf(delete(BASE + "/connection")).cookie(login(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(BASE + "/callback?code=a&state=b").cookie(login(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void connectNeedsACsrfToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(BASE + "/connect").cookie(login(Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void disconnectRevokesAndForgets() throws Exception {
        connections.save(new ZohoConnection(REFRESH_TOKEN, "admin@example.test"));
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token/revoke")).willReturn(okJson("{}")));

        mockMvc.perform(withCsrf(delete(BASE + "/connection")).cookie(login(Role.ADMIN)))
                .andExpect(status().isNoContent());

        ZOHO.verify(postRequestedFor(urlEqualTo("/oauth/v2/token/revoke"))
                .withRequestBody(containing("token=" + REFRESH_TOKEN))
                .withRequestBody(notContaining(CLIENT_SECRET)));
        assertThat(connections.count()).isZero();
    }

    @Test
    void disconnectWorksEvenIfZohoIsDown() throws Exception {
        connections.save(new ZohoConnection(REFRESH_TOKEN, "admin@example.test"));
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token/revoke"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));

        mockMvc.perform(withCsrf(delete(BASE + "/connection")).cookie(login(Role.ADMIN)))
                .andExpect(status().isNoContent());

        assertThat(connections.count()).isZero();
    }

    @Test
    void pickerEndpointsNeedARoleAndExplainWhenDisconnected() throws Exception {
        mockMvc.perform(get(BASE + "/portals").cookie(login(Role.VIEWER))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/portals").cookie(login(Role.ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Zoho isn't connected yet. An admin can connect it in Settings."));
    }

    private org.springframework.test.web.servlet.ResultActions callback(Cookie auth, Cookie state, String query) throws Exception {
        var request = get(BASE + "/callback?" + query).cookie(auth);
        if (state != null) {
            request.cookie(state);
        }
        return mockMvc.perform(request);
    }

    private MvcResult connect(Cookie auth) throws Exception {
        return mockMvc.perform(withCsrf(MockMvcRequestBuilders.post(BASE + "/connect")).cookie(auth))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
    }

    private Cookie login(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return new Cookie("GK_TOKEN", tokens.issue(users.save(user).getId()));
    }
}
