package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.InMemoryObjectStorage;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ProjectEditIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "first-password";
    private static final String TOKEN = "first-figma-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private TestAccountRepository testAccounts;
    @Autowired private DesignSourceRepository designSources;
    @Autowired private DesignUploadRepository designUploads;
    @Autowired private ProjectRepository projects;
    @Autowired private TestRunRepository runs;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private InMemoryObjectStorage storage;

    private UUID projectId;
    private JsonNode created;

    @BeforeEach
    void createProject() throws Exception {
        String body = mockMvc.perform(withCsrf(post("/api/projects"))
                        .cookie(login(Role.QA_LEAD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        created = objectMapper.readTree(body);
        projectId = UUID.fromString(created.get("id").asText());
    }

    @Test
    void replacesContentsAndBumpsVersion() throws Exception {
        update(request -> {
                    request.put("name", "Renamed");
                    list(request, "repositories").removeLast();
                    list(request, "features").add(new HashMap<>(Map.of("title", "Search", "priority", "LOW")));
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.repositories.length()").value(1))
                .andExpect(jsonPath("$.features.length()").value(2))
                .andExpect(jsonPath("$.version").value(created.get("version").asLong() + 1));
    }

    @Test
    void repositoriesCanBeResubmittedUnchanged() throws Exception {
        update(request -> {}).andExpect(status().isOk());
        update(request -> request.put("version", currentVersion())).andExpect(status().isOk());
    }

    @Test
    void staleVersionIsRejectedSoOtherPeoplesChangesArentLost() throws Exception {
        update(request -> {}).andExpect(status().isOk());

        update(request -> request.put("name", "Overwrites")).andExpect(status().isConflict());

        assertThat(projects.findById(projectId).orElseThrow().getName()).isEqualTo("Shop");
    }

    @Test
    void versionIsRequired() throws Exception {
        update(request -> request.remove("version"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("version")));
    }

    @Test
    void storedPasswordAndTokenAreKeptWhenLeftEmpty() throws Exception {
        update(request -> {}).andExpect(status().isOk());

        assertThat(testAccounts.findByProjectId(projectId).getFirst().getPassword()).isEqualTo(PASSWORD);
        assertThat(designSources.findByProjectId(projectId).getFirst().getToken()).isEqualTo(TOKEN);
    }

    @Test
    void newPasswordAndTokenReplaceStoredOnes() throws Exception {
        update(request -> {
                    accounts(request).getFirst().put("password", "second-password");
                    design(request).put("token", "second-token");
                })
                .andExpect(status().isOk());

        assertThat(testAccounts.findByProjectId(projectId).getFirst().getPassword()).isEqualTo("second-password");
        assertThat(designSources.findByProjectId(projectId).getFirst().getToken()).isEqualTo("second-token");
    }

    @Test
    void storedPasswordCantBeSentToADifferentLoginSite() throws Exception {
        update(request -> {
                    request.put("stagingUrl", "https://attacker.test");
                    accounts(request).getFirst().put("loginUrl", "https://attacker.test/login");
                })
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("testAccounts[0].password")));

        assertThat(testAccounts.findByProjectId(projectId).getFirst().getLoginUrl()).isEqualTo("https://app.test/login");
    }

    @Test
    void moveToNewSiteIsAllowedWithNewPassword() throws Exception {
        update(request -> {
                    request.put("stagingUrl", "https://new.test");
                    accounts(request).getFirst().put("loginUrl", "https://new.test/login");
                    accounts(request).getFirst().put("password", "second-password");
                })
                .andExpect(status().isOk());
    }

    @Test
    void loginPathOnSameHostCanChangeWithoutPassword() throws Exception {
        update(request -> accounts(request).getFirst().put("loginUrl", "https://app.test/signin"))
                .andExpect(status().isOk());
    }

    @Test
    void anAccountFromAnotherProjectCantBeReferenced() throws Exception {
        UUID stranger = UUID.randomUUID();

        update(request -> accounts(request).getFirst().put("id", stranger.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("testAccounts[0].id")));
    }

    @Test
    void removedAccountIsDeletedAndNewAccountNeedsPassword() throws Exception {
        update(request -> accounts(request).add(new HashMap<>(Map.of(
                        "role", "viewer", "username", "v@app.test", "loginUrl", "https://app.test/login"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("testAccounts[1].password")));

        update(request -> request.put("testAccounts", List.of())).andExpect(status().isOk());

        assertThat(testAccounts.findByProjectId(projectId)).isEmpty();
    }

    @Test
    void anAccountCanBeReplacedByANewOneWithTheSameRoleAndUsername() throws Exception {
        update(request -> {
                    accounts(request).getFirst().remove("id");
                    accounts(request).getFirst().put("password", "second-password");
                })
                .andExpect(status().isOk());

        assertThat(testAccounts.findByProjectId(projectId)).singleElement()
                .satisfies(account -> assertThat(account.getPassword()).isEqualTo("second-password"));
    }

    @Test
    void switchingDesignTypeRemovesOldCredentialsAndFiles() throws Exception {
        update(request -> request.put("design", Map.of("type", "UPLOAD"))).andExpect(status().isOk());
        DesignSource uploadSource = designSources.findByProjectId(projectId).getFirst();
        assertThat(uploadSource.getToken()).isNull();
        String key = "projects/" + projectId + "/design/x.png";
        storage.put(key, new ByteArrayInputStream(new byte[1]), 1, "image/png");
        designUploads.save(new DesignUpload(uploadSource.getId(), "x.png", key, "image/png", 1));

        update(request -> {
                    request.put("version", currentVersion());
                    request.remove("design");
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.design").doesNotExist());

        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void switchingToFigmaAlwaysNeedsAToken() throws Exception {
        update(request -> request.put("design", Map.of("type", "UPLOAD"))).andExpect(status().isOk());

        update(request -> {
                    request.put("version", currentVersion());
                    design(request).remove("token");
                })
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("design.token")));
    }

    @Test
    void updateIsValidatedAndAuditedWithoutSecrets() throws Exception {
        update(request -> request.put("stagingUrl", "https://www.corp.example"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("stagingUrl")));

        update(request -> accounts(request).getFirst().put("password", "second-password")).andExpect(status().isOk());

        var event = auditEvents.findAll().stream()
                .filter(item -> "PROJECT_UPDATE".equals(item.getAction())
                        && projectId.toString().equals(item.getTarget())
                        && item.getDetailsJson().contains("SUCCESS"))
                .findFirst()
                .orElseThrow();
        assertThat(event.getDetailsJson()).contains("\"passwordsChanged\": 1").doesNotContain("second-password");
    }

    @Test
    void viewerCantEditAndOnlyAdminsCanDelete() throws Exception {
        mockMvc.perform(withCsrf(put("/api/projects/" + projectId))
                        .cookie(login(Role.VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editRequest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(delete("/api/projects/" + projectId)).cookie(login(Role.QA_LEAD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRemovesProjectRowsAndFiles() throws Exception {
        update(request -> request.put("design", Map.of("type", "UPLOAD"))).andExpect(status().isOk());
        DesignSource source = designSources.findByProjectId(projectId).getFirst();
        String key = "projects/" + projectId + "/design/y.png";
        storage.put(key, new ByteArrayInputStream(new byte[1]), 1, "image/png");
        designUploads.save(new DesignUpload(source.getId(), "y.png", key, "image/png", 1));

        mockMvc.perform(withCsrf(delete("/api/projects/" + projectId)).cookie(login(Role.ADMIN)))
                .andExpect(status().isNoContent());

        assertThat(projects.existsById(projectId)).isFalse();
        assertThat(testAccounts.findByProjectId(projectId)).isEmpty();
        assertThat(storage.objects).doesNotContainKey(key);
        mockMvc.perform(get("/api/projects/" + projectId).cookie(login(Role.VIEWER))).andExpect(status().isNotFound());
    }

    @Test
    void projectWithRunsCantBeDeleted() throws Exception {
        runs.save(new TestRun(projectId));

        mockMvc.perform(withCsrf(delete("/api/projects/" + projectId)).cookie(login(Role.ADMIN)))
                .andExpect(status().isConflict());

        assertThat(projects.existsById(projectId)).isTrue();
    }

    @Test
    void singleImageCanBeRemovedButOnlyThroughItsOwnProject() throws Exception {
        update(request -> request.put("design", Map.of("type", "UPLOAD"))).andExpect(status().isOk());
        DesignSource source = designSources.findByProjectId(projectId).getFirst();
        String key = "projects/" + projectId + "/design/z.png";
        storage.put(key, new ByteArrayInputStream(new byte[1]), 1, "image/png");
        UUID uploadId = designUploads.save(new DesignUpload(source.getId(), "z.png", key, "image/png", 1)).getId();

        UUID otherProject = projects.save(new Project("Other", "https://o.test", "x")).getId();
        mockMvc.perform(withCsrf(delete("/api/projects/" + otherProject + "/design-uploads/" + uploadId))
                        .cookie(login(Role.ADMIN)))
                .andExpect(status().isNotFound());
        assertThat(storage.objects).containsKey(key);

        mockMvc.perform(withCsrf(delete("/api/projects/" + projectId + "/design-uploads/" + uploadId))
                        .cookie(login(Role.QA_LEAD)))
                .andExpect(status().isNoContent());
        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(designUploads.existsById(uploadId)).isFalse();
    }

    private long currentVersion() {
        return projects.findById(projectId).orElseThrow().getVersion();
    }

    private ResultActions update(Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> request = editRequest();
        change.accept(request);
        return mockMvc.perform(withCsrf(put("/api/projects/" + projectId))
                .cookie(login(Role.QA_LEAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    /** What the edit screen sends back when nothing is changed: no password and no token. */
    private Map<String, Object> editRequest() {
        Map<String, Object> request = newRequest();
        request.put("version", created.get("version").asLong());
        accounts(request).getFirst().remove("password");
        accounts(request).getFirst().put("id", created.get("testAccounts").get(0).get("id").asText());
        design(request).remove("token");
        return request;
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Shop");
        request.put("zohoPortalId", "portal-1");
        request.put("zohoProjectId", "project-1");
        request.put("stagingUrl", "https://app.test");
        request.put("design", new HashMap<>(Map.of(
                "type", "FIGMA", "figmaUrl", "https://www.figma.com/design/AbC123/File", "token", TOKEN)));
        request.put("repositories", new ArrayList<>(List.of(
                new HashMap<>(Map.of("type", "FRONTEND", "repoFullName", "org/web", "defaultBranch", "main")),
                new HashMap<>(Map.of("type", "BACKEND", "repoFullName", "org/api", "defaultBranch", "main")))));
        request.put("testAccounts", new ArrayList<>(List.of(new HashMap<String, Object>(Map.of(
                "role", "admin", "username", "qa@app.test", "password", PASSWORD, "loginUrl", "https://app.test/login")))));
        request.put("features", new ArrayList<>(List.of(new HashMap<>(Map.of("title", "Login", "priority", "HIGH")))));
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
        Object design = request.get("design");
        if (!(design instanceof HashMap)) {
            Map<String, Object> figma = new HashMap<>(Map.of(
                    "type", "FIGMA", "figmaUrl", "https://www.figma.com/design/AbC123/File"));
            request.put("design", figma);
            return figma;
        }
        return (Map<String, Object>) design;
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
