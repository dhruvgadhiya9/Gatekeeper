package com.gatekeeper.qaplatform.routing;

import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.ZOHO;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import com.gatekeeper.qaplatform.integration.zoho.ZohoConnection;
import com.gatekeeper.qaplatform.integration.zoho.ZohoConnectionRepository;
import com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class RoutingSettingsIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private UserMappingRepository userMappings;
    @Autowired private ZohoConnectionRepository connections;
    @Autowired private AuditEventRepository auditEvents;

    @DynamicPropertySource
    static void zoho(DynamicPropertyRegistry registry) {
        ZohoTestSupport.register(registry);
    }

    @BeforeEach
    void reset() {
        ZOHO.resetAll();
        ZohoTestSupport.stubTokens();
        connections.deleteAll();
    }

    @Test
    void adminManagesUserMappingsWithUsernamesNormalized() throws Exception {
        String username = "User-" + UUID.randomUUID().toString().substring(0, 8);

        String body = send(MockMvcRequestBuilders.post("/api/user-mappings"), Role.ADMIN,
                        Map.of("githubUsername", username, "zohoUserId", "238"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.githubUsername").value(username.toLowerCase()))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        send(MockMvcRequestBuilders.post("/api/user-mappings"), Role.ADMIN,
                        Map.of("githubUsername", username.toUpperCase(), "zohoUserId", "239"))
                .andExpect(status().isConflict());

        send(MockMvcRequestBuilders.put("/api/user-mappings/" + id), Role.ADMIN, Map.of("zohoUserId", "240"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zohoUserId").value("240"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/user-mappings").cookie(login(Role.ADMIN)))
                .andExpect(jsonPath("$[*].githubUsername", hasItem(username.toLowerCase())));

        mockMvc.perform(withCsrf(MockMvcRequestBuilders.delete("/api/user-mappings/" + id)).cookie(login(Role.ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(userMappings.findById(UUID.fromString(id))).isEmpty();
        assertThat(auditEvents.findAll()).anyMatch(event -> "USER_MAPPING_DELETE".equals(event.getAction()));
    }

    @Test
    void invalidMappingsAreRejected() throws Exception {
        send(MockMvcRequestBuilders.post("/api/user-mappings"), Role.ADMIN,
                        Map.of("githubUsername", "bad name!", "zohoUserId", "238"))
                .andExpect(status().isBadRequest());
        send(MockMvcRequestBuilders.post("/api/user-mappings"), Role.ADMIN,
                        Map.of("githubUsername", "ok", "zohoUserId", "bad id!"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settingsAreForAdminsOnly() throws Exception {
        for (Role role : List.of(Role.QA_LEAD, Role.VIEWER)) {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/user-mappings").cookie(login(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(MockMvcRequestBuilders.get("/api/integrations/zoho/portals/1/field-options").cookie(login(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void fieldOptionsAreReplacedPerPortal() throws Exception {
        String url = "/api/integrations/zoho/portals/77/field-options";
        send(MockMvcRequestBuilders.put(url), Role.ADMIN,
                        Map.of("severity", Map.of("HIGH", "11", "LOW", "12"), "classification", Map.of("BACKEND", "22")))
                .andExpect(status().isOk());
        send(MockMvcRequestBuilders.put(url), Role.ADMIN, Map.of("severity", Map.of("HIGH", "13"), "classification", Map.of()))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.get(url).cookie(login(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity.HIGH").value("13"))
                .andExpect(jsonPath("$.severity.LOW").doesNotExist())
                .andExpect(jsonPath("$.classification.BACKEND").doesNotExist());
    }

    @Test
    void unknownOptionKeysAndBadIdsAreRejected() throws Exception {
        String url = "/api/integrations/zoho/portals/77/field-options";
        send(MockMvcRequestBuilders.put(url), Role.ADMIN, Map.of("severity", Map.of("URGENT", "11"), "classification", Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("severity.URGENT")));
        send(MockMvcRequestBuilders.put(url), Role.ADMIN, Map.of("severity", Map.of("HIGH", "bad id"), "classification", Map.of()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void whenZohoIsConnectedAProjectsIdsMustExistThere() throws Exception {
        connections.save(new ZohoConnection("refresh", "admin@example.test"));
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals")).willReturn(okJson("{\"portals\":[{\"id\":\"1\",\"name\":\"Acme\"}]}")));
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3/portal/1/projects")).willReturn(okJson("[{\"id\":\"2\",\"name\":\"Shop\"}]")));
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3/portal/1/projects/2/users"))
                .willReturn(okJson("{\"users\":[{\"id\":\"57\",\"zpuid\":\"238\",\"full_name\":\"N\"}]}")));

        createProject("1", "2", "238").andExpect(status().isCreated());
        createProject("9", "2", "238").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("zohoPortalId"));
        createProject("1", "9", "238").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("zohoProjectId"));
        createProject("1", "2", "999").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("departmentMappings[0].defaultZohoUserId")));
    }

    @Test
    void whenZohoIsNotConnectedTypedIdsAreAccepted() throws Exception {
        createProject("typed", "ids", "here").andExpect(status().isCreated());
        ZOHO.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/api/v3.1/portals")));
    }

    private ResultActions createProject(String portal, String project, String user) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Demo");
        request.put("zohoPortalId", portal);
        request.put("zohoProjectId", project);
        request.put("stagingUrl", "https://app.test");
        request.put("repositories", List.of(Map.of("type", "BACKEND", "repoFullName", "acme/web", "defaultBranch", "main")));
        request.put("testAccounts", List.of());
        request.put("features", List.of(Map.of("title", "Login", "priority", "HIGH")));
        request.put("departmentMappings", List.of(
                Map.of("category", "DESIGN", "defaultZohoUserId", user, "fallbackZohoUserId", user),
                Map.of("category", "FRONTEND", "defaultZohoUserId", user, "fallbackZohoUserId", user),
                Map.of("category", "BACKEND", "defaultZohoUserId", user, "fallbackZohoUserId", user),
                Map.of("category", "SECURITY", "defaultZohoUserId", user, "fallbackZohoUserId", user, "securityLeadZohoUserId", user)));
        return send(MockMvcRequestBuilders.post("/api/projects"), Role.ADMIN, request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Role role, Map<String, ?> body) throws Exception {
        return mockMvc.perform(withCsrf(request)
                .cookie(login(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
    }

    private Cookie login(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return new Cookie("GK_TOKEN", tokens.issue(users.save(user).getId()));
    }
}
