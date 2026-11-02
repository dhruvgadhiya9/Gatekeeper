package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class ProjectIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "plain-test-password";
    private static final String FIGMA_TOKEN = "plain-figma-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private ProjectRepository projects;

    @Test
    void qaLeadCreatesProjectAndEverythingInItIsSaved() throws Exception {
        String body = create(Role.QA_LEAD, request -> {})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.design.figmaFileKey").value("AbC123"))
                .andExpect(jsonPath("$.design.tokenStored").value(true))
                .andExpect(jsonPath("$.repositories.length()").value(2))
                .andExpect(jsonPath("$.testAccounts[0].username").value("qa@app.test"))
                .andExpect(jsonPath("$.features[0].title").value("Login"))
                .andExpect(jsonPath("$.departmentMappings.length()").value(4))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD, FIGMA_TOKEN);
    }

    @Test
    void credentialsAreEncryptedInTheDatabaseAndNeverReturned() throws Exception {
        UUID id = createdId();

        assertThat(jdbc.queryForObject(
                        "select encrypted_password from test_account where project_id = ?", String.class, id))
                .doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject(
                        "select encrypted_token from design_source where project_id = ?", String.class, id))
                .doesNotContain(FIGMA_TOKEN);

        String detail = mockMvc.perform(get("/api/projects/" + id).cookie(login(Role.VIEWER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(PASSWORD, FIGMA_TOKEN, "encrypted").contains("qa@app.test");
    }

    @Test
    void creationIsAuditedWithoutSecrets() throws Exception {
        UUID id = createdId();

        var event = auditEvents.findAll().stream()
                .filter(item -> "PROJECT_CREATE".equals(item.getAction()) && id.toString().equals(item.getTarget()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getDetailsJson()).contains("SUCCESS").doesNotContain(PASSWORD, FIGMA_TOKEN);
    }

    @Test
    void viewerCanReadButNotCreate() throws Exception {
        create(Role.VIEWER, request -> {}).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/projects").cookie(login(Role.VIEWER))).andExpect(status().isOk());
    }

    @Test
    void anonymousCannotReadProjects() throws Exception {
        mockMvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void listIsPagedWithNewestFirst() throws Exception {
        UUID older = createdId();
        UUID newer = createdId();

        mockMvc.perform(get("/api/projects?size=100").cookie(login(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.items[*].id", hasItem(older.toString())))
                .andExpect(jsonPath("$.items[0].testAccounts").doesNotExist());
    }

    @Test
    void invalidPageParametersAreRejected() throws Exception {
        Cookie cookie = login(Role.VIEWER);
        mockMvc.perform(get("/api/projects?size=1000").cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/projects?page=-1").cookie(cookie)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/projects/" + UUID.randomUUID()).cookie(login(Role.VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void productionStagingUrlIsRejectedAndNothingIsSaved() throws Exception {
        long before = projects.count();

        expectViolation(request -> request.put("stagingUrl", "https://www.corp.example"), "stagingUrl");

        assertThat(projects.count()).isEqualTo(before);
    }

    @Test
    void localStagingUrlIsRejected() throws Exception {
        expectViolation(request -> request.put("stagingUrl", "http://169.254.169.254"), "stagingUrl");
    }

    @Test
    void loginUrlOnAnotherSiteIsRejectedSoCredentialsCantBeSentThere() throws Exception {
        expectViolation(
                request -> accounts(request).getFirst().put("loginUrl", "https://evil.test/login"),
                "testAccounts[0].loginUrl");
    }

    @Test
    void loginUrlOnSubdomainIsAccepted() throws Exception {
        create(Role.ADMIN, request -> accounts(request).getFirst().put("loginUrl", "https://auth.app.test/login"))
                .andExpect(status().isCreated());
    }

    @Test
    void fieldErrorsIdentifyTheExactRow() throws Exception {
        expectViolation(request -> list(request, "repositories").get(1).put("repoFullName", "not a repo"),
                "repositories[1].repoFullName");
        expectViolation(request -> list(request, "features").getFirst().put("title", " "), "features[0].title");
        expectViolation(request -> request.put("zohoPortalId", "bad id!"), "zohoPortalId");
        expectViolation(request -> accounts(request).getFirst().put("password", ""), "testAccounts[0].password");
    }

    @Test
    void validationErrorsNeverEchoTheSubmittedPassword() throws Exception {
        String response = create(Role.ADMIN, request -> accounts(request).getFirst().put("username", ""))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(PASSWORD);
    }

    @Test
    void duplicateRepositoriesAndAccountsAreRejected() throws Exception {
        expectViolation(
                request -> list(request, "repositories").get(1).put("repoFullName", "ORG/web"),
                "repositories[1].repoFullName");
        expectViolation(request -> accounts(request).add(new HashMap<>(accounts(request).getFirst())),
                "testAccounts[1].username");
    }

    @Test
    void projectNeedsRepositoryFeatureAndAllDepartments() throws Exception {
        expectViolation(request -> request.put("repositories", List.of()), "repositories");
        expectViolation(request -> request.put("features", List.of()), "features");
        expectViolation(request -> list(request, "departmentMappings").removeLast(), "departmentMappings");
    }

    @Test
    void securityDepartmentNeedsSecurityLead() throws Exception {
        expectViolation(
                request -> list(request, "departmentMappings").getLast().remove("securityLeadZohoUserId"),
                "departmentMappings[3].securityLeadZohoUserId");
    }

    @Test
    void figmaDesignNeedsValidLinkAndToken() throws Exception {
        expectViolation(request -> design(request).put("figmaUrl", "https://evil.test/design/x"), "design.figmaUrl");
        expectViolation(request -> design(request).remove("token"), "design.token");
    }

    @Test
    void designIsOptional() throws Exception {
        create(Role.ADMIN, request -> request.remove("design"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.design").doesNotExist());
    }

    @Test
    void csvIsParsedButNotSaved() throws Exception {
        long before = jdbc.queryForObject("select count(*) from feature_spec", Long.class);
        var file = new MockMultipartFile("file", "f.csv", "text/csv", "title,priority\nLogin,high\n,low\n".getBytes());

        mockMvc.perform(withCsrf(multipart("/api/projects/feature-csv").file(file).cookie(login(Role.QA_LEAD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[0].title").value("Login"))
                .andExpect(jsonPath("$.errors[0].row").value(3));

        assertThat(jdbc.queryForObject("select count(*) from feature_spec", Long.class)).isEqualTo(before);
    }

    @Test
    void invalidCsvReturnsBadRequestAndViewerIsForbidden() throws Exception {
        var file = new MockMultipartFile("file", "f.csv", "text/csv", "name\nLogin\n".getBytes());

        mockMvc.perform(withCsrf(multipart("/api/projects/feature-csv").file(file).cookie(login(Role.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        mockMvc.perform(withCsrf(multipart("/api/projects/feature-csv").file(file).cookie(login(Role.VIEWER))))
                .andExpect(status().isForbidden());
    }

    private void expectViolation(Consumer<Map<String, Object>> change, String field) throws Exception {
        create(Role.ADMIN, change)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    private UUID createdId() throws Exception {
        String body = create(Role.ADMIN, request -> {})
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private ResultActions create(Role role, Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> request = validRequest();
        change.accept(request);
        return mockMvc.perform(withCsrf(post("/api/projects")
                .cookie(login(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))));
    }

    private static Map<String, Object> validRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Demo");
        request.put("zohoPortalId", "portal-1");
        request.put("zohoProjectId", "project-1");
        request.put("stagingUrl", "https://app.test");
        request.put("design", new HashMap<>(Map.of(
                "type", "FIGMA", "figmaUrl", "https://www.figma.com/design/AbC123/File", "token", FIGMA_TOKEN)));
        request.put("repositories", new ArrayList<>(List.of(
                new HashMap<>(Map.of("type", "FRONTEND", "repoFullName", "org/web", "defaultBranch", "main")),
                new HashMap<>(Map.of("type", "BACKEND", "repoFullName", "org/api", "defaultBranch", "main")))));
        request.put("testAccounts", new ArrayList<>(List.of(new HashMap<>(Map.of(
                "role", "admin", "username", "qa@app.test", "password", PASSWORD, "loginUrl", "https://app.test/login")))));
        request.put("features", new ArrayList<>(List.of(new HashMap<>(Map.of(
                "title", "Login", "description", "d", "acceptanceCriteria", "a", "priority", "HIGH")))));
        request.put("departmentMappings", new ArrayList<>(List.of(
                mapping("DESIGN"), mapping("FRONTEND"), mapping("BACKEND"),
                new HashMap<>(Map.of(
                        "category", "SECURITY",
                        "defaultZohoUserId", "u1",
                        "fallbackZohoUserId", "u2",
                        "securityLeadZohoUserId", "u3")))));
        return request;
    }

    private static Map<String, Object> mapping(String category) {
        return new HashMap<>(Map.of("category", category, "defaultZohoUserId", "u1", "fallbackZohoUserId", "u2"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> request, String key) {
        return (List<Map<String, Object>>) request.get(key);
    }

    private static List<Map<String, Object>> accounts(Map<String, Object> request) {
        return list(request, "testAccounts");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> design(Map<String, Object> request) {
        return (Map<String, Object>) request.get("design");
    }

    private static <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T withCsrf(T request) {
        request.cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t");
        return request;
    }

    private Cookie login(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return new Cookie("GK_TOKEN", tokens.issue(users.save(user).getId()));
    }
}
