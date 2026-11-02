package com.gatekeeper.qaplatform.integration.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.BlameRange;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.CloneCredentials;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(OutputCaptureExtension.class)
class GitHubClientTest extends IntegrationTestBase {

    private static final String TOKEN = "ghs_secret_installation_token";
    private static final String EXPIRES = "2099-01-01T00:00:00Z";
    private static final String TOKEN_JSON = "{\"token\":\"" + TOKEN + "\",\"expires_at\":\"" + EXPIRES + "\"}";
    private static final WireMockServer GITHUB = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final KeyPair KEYS = generateKeys();

    /** Each test uses its own installation, because tokens are cached per installation. */
    private long installation;

    @Autowired private GitHubClient client;
    @Autowired private CircuitBreakerRegistry circuitBreakers;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;

    private static long nextInstallation = 100;

    @DynamicPropertySource
    static void githubProperties(DynamicPropertyRegistry registry) {
        GITHUB.start();
        registry.add("app.github.api-url", GITHUB::baseUrl);
        registry.add("app.github.app-id", () -> "12345");
        registry.add("app.github.private-key", () -> Base64.getEncoder().encodeToString(pkcs1Pem().getBytes()));
        registry.add("resilience4j.retry.configs.external.wait-duration", () -> "1ms");
    }

    @AfterAll
    static void stop() {
        GITHUB.stop();
    }

    @BeforeEach
    void reset() {
        GITHUB.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        installation = ++nextInstallation;
    }

    @Test
    void appJwtIsSignedWithThePkcs1KeyAndHasGitHubsRequiredClaims() throws Exception {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations")).willReturn(okJson("[]")));

        client.listInstallations();

        var request = GITHUB.findAll(getRequestedFor(urlPathEqualTo("/app/installations"))).getFirst();
        SignedJWT jwt = SignedJWT.parse(request.getHeader("Authorization").replace("Bearer ", ""));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) KEYS.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("12345");
        long lifetimeSeconds = (jwt.getJWTClaimsSet().getExpirationTime().getTime()
                        - jwt.getJWTClaimsSet().getIssueTime().getTime())
                / 1000;
        assertThat(lifetimeSeconds).isLessThanOrEqualTo(600);
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2026-03-10");
    }

    @Test
    void listsInstallations() {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations"))
                .willReturn(okJson("[{\"id\":7,\"account\":{\"login\":\"acme\",\"type\":\"Organization\"}}]")));

        assertThat(client.listInstallations()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(7);
            assertThat(item.account()).isEqualTo("acme");
        });
    }

    @Test
    void listsRepositoriesAcrossPages() throws Exception {
        stubInstallationToken();
        List<Map<String, Object>> fullPage = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullPage.add(Map.of("full_name", "acme/r" + i, "default_branch", "main", "private", true));
        }
        GITHUB.stubFor(get(urlEqualTo("/installation/repositories?per_page=100&page=1"))
                .willReturn(okJson(objectMapper.writeValueAsString(Map.of("repositories", fullPage)))));
        GITHUB.stubFor(get(urlEqualTo("/installation/repositories?per_page=100&page=2"))
                .willReturn(okJson("{\"repositories\":[{\"full_name\":\"acme/last\",\"default_branch\":\"dev\",\"private\":false}]}")));

        var repositories = client.listRepositories(installation);

        assertThat(repositories).hasSize(101);
        assertThat(repositories.getLast().fullName()).isEqualTo("acme/last");
        assertThat(repositories.getLast().defaultBranch()).isEqualTo("dev");
    }

    @Test
    void installationTokenIsReusedUntilCloseToExpiry() {
        stubInstallationToken();
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories")).willReturn(okJson("{\"repositories\":[]}")));

        client.listRepositories(installation);
        client.listRepositories(installation);

        GITHUB.verify(1, postRequestedFor(urlEqualTo("/app/installations/" + installation + "/access_tokens")));
        GITHUB.verify(2, getRequestedFor(urlPathEqualTo("/installation/repositories"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void almostExpiredTokenIsRenewed() {
        GITHUB.stubFor(post(urlEqualTo("/app/installations/" + installation + "/access_tokens"))
                .willReturn(okJson("{\"token\":\"t\",\"expires_at\":\"2020-01-01T00:00:00Z\"}")));
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories")).willReturn(okJson("{\"repositories\":[]}")));

        client.listRepositories(installation);
        client.listRepositories(installation);

        GITHUB.verify(2, postRequestedFor(urlEqualTo("/app/installations/" + installation + "/access_tokens")));
    }

    @Test
    void cloneTokenIsLimitedToOneRepositoryAndReadOnly() {
        stubInstallationToken();

        CloneCredentials credentials = client.createCloneCredentials(installation, "acme/web");

        GITHUB.verify(postRequestedFor(urlEqualTo("/app/installations/" + installation + "/access_tokens"))
                .withRequestBody(matchingJsonPath("$.repositories[0]", equalTo("web")))
                .withRequestBody(matchingJsonPath("$.permissions.contents", equalTo("read")))
                .withRequestBody(matchingJsonPath("$.permissions[?(@.pull_requests)]", com.github.tomakehurst.wiremock.client.WireMock.absent())));
        assertThat(credentials.cloneUrl()).isEqualTo("https://github.com/acme/web.git").doesNotContain(TOKEN);
        assertThat(credentials.token()).isEqualTo(TOKEN);
        assertThat(credentials.toString()).doesNotContain(TOKEN);
    }

    @Test
    void readsCodeownersFromTheFirstPlaceItExists() {
        stubInstallationToken();
        GITHUB.stubFor(get(urlEqualTo("/repos/acme/web/contents/.github/CODEOWNERS")).willReturn(notFound()));
        GITHUB.stubFor(get(urlEqualTo("/repos/acme/web/contents/CODEOWNERS"))
                .willReturn(aResponse().withStatus(200).withBody("* @acme/team")));

        assertThat(client.readCodeowners(installation, "acme/web")).contains("* @acme/team");
        GITHUB.verify(0, getRequestedFor(urlEqualTo("/repos/acme/web/contents/docs/CODEOWNERS")));
    }

    @Test
    void missingCodeownersIsEmptyNotAnError() {
        stubInstallationToken();
        GITHUB.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/repos/acme/web/contents/.*"))
                .willReturn(notFound()));

        assertThat(client.readCodeowners(installation, "acme/web")).isEmpty();
    }

    @Test
    void blameReturnsOnlyOverlappingRangesAndUsesVariables() {
        stubInstallationToken();
        GITHUB.stubFor(post(urlEqualTo("/graphql")).willReturn(okJson("""
                {"data":{"repository":{"object":{"blame":{"ranges":[
                  {"startingLine":1,"endingLine":9,"commit":{"oid":"a","committedDate":"2026-01-01T00:00:00Z","author":{"email":"a@x.test","user":{"login":"alice"}}}},
                  {"startingLine":10,"endingLine":20,"commit":{"oid":"b","committedDate":"2026-02-01T00:00:00Z","author":{"email":"b@x.test","user":null}}},
                  {"startingLine":21,"endingLine":30,"commit":{"oid":"c","committedDate":"2026-03-01T00:00:00Z","author":{"email":"c@x.test","user":{"login":"carol"}}}}
                ]}}}}}
                """)));

        List<BlameRange> ranges = client.blame(installation, "acme/web", "main", "src/a\") { evil }.java", 5, 12);

        assertThat(ranges).extracting(BlameRange::commitSha).containsExactly("a", "b");
        assertThat(ranges.get(0).authorLogin()).isEqualTo("alice");
        assertThat(ranges.get(1).authorLogin()).isNull();
        assertThat(ranges.get(1).authorEmail()).isEqualTo("b@x.test");
        GITHUB.verify(postRequestedFor(urlEqualTo("/graphql"))
                .withRequestBody(matchingJsonPath("$.variables.path", equalTo("src/a\") { evil }.java")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*query.*evil.*query.*")));
    }

    @Test
    void blameOfMissingFileIsEmpty() {
        stubInstallationToken();
        GITHUB.stubFor(post(urlEqualTo("/graphql"))
                .willReturn(okJson("{\"data\":{\"repository\":{\"object\":null}},\"errors\":[{\"message\":\"nope\"}]}")));

        assertThat(client.blame(installation, "acme/web", "main", "missing.java", 1, 2)).isEmpty();
    }

    @Test
    void invalidRepositoryNameNeverReachesGitHub() {
        assertThatThrownBy(() -> client.readCodeowners(installation, "../../admin"))
                .isInstanceOf(com.gatekeeper.qaplatform.common.error.RequestValidationException.class);
    }

    @Test
    void temporaryFailuresAreRetried() {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations"))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("recovered"));
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("[]")));

        assertThat(client.listInstallations()).isEmpty();

        GITHUB.verify(2, getRequestedFor(urlPathEqualTo("/app/installations")));
    }

    @Test
    void permanentFailuresAreNotRetried() {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.listInstallations()).isInstanceOf(HttpClientErrorException.Unauthorized.class);

        GITHUB.verify(1, getRequestedFor(urlPathEqualTo("/app/installations")));
    }

    @Test
    void repeatedFailuresOpenTheCircuitSoGitHubStopsBeingCalled() {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations")).willReturn(serverError()));
        // Every retry counts as a failure, so the circuit opens part-way through these calls.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.listInstallations())
                    .isInstanceOfAny(HttpServerErrorException.class, CallNotPermittedException.class);
        }
        GITHUB.resetRequests();

        assertThatThrownBy(() -> client.listInstallations()).isInstanceOf(CallNotPermittedException.class);

        GITHUB.verify(0, getRequestedFor(urlPathEqualTo("/app/installations")));
    }

    @Test
    void tokensNeverReachTheLogsEvenWhenCallsFail(CapturedOutput output) throws Exception {
        stubInstallationToken();
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories")).willReturn(serverError()));

        assertThatThrownBy(() -> client.listRepositories(installation)).isInstanceOf(HttpServerErrorException.class);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/integrations/github/installations/" + installation + "/repositories")
                        .cookie(login(Role.ADMIN)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));

        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void pickerEndpointsAreForAdminsAndQaLeads() throws Exception {
        GITHUB.stubFor(get(urlPathEqualTo("/app/installations"))
                .willReturn(okJson("[{\"id\":7,\"account\":{\"login\":\"acme\",\"type\":\"Organization\"}}]")));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/integrations/github/installations").cookie(login(Role.QA_LEAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account").value("acme"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/integrations/github/installations").cookie(login(Role.VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/integrations/github/status").cookie(login(Role.VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void projectRepositoriesMustBeReachableByTheApp() throws Exception {
        stubInstallationToken();
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories"))
                .willReturn(okJson("{\"repositories\":[{\"full_name\":\"Acme/Web\",\"default_branch\":\"main\",\"private\":true}]}")));

        createProject(Map.of("type", "FRONTEND", "repoFullName", "acme/web", "defaultBranch", "main", "installationId", installation))
                .andExpect(status().isCreated());
        createProject(Map.of("type", "BACKEND", "repoFullName", "acme/other", "defaultBranch", "main", "installationId", installation))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("repositories[0].repoFullName")));
        createProject(Map.of("type", "BACKEND", "repoFullName", "acme/web", "defaultBranch", "main"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("repositories[0].installationId")));
    }

    private org.springframework.test.web.servlet.ResultActions createProject(Map<String, Object> repository) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Demo");
        request.put("zohoPortalId", "p");
        request.put("zohoProjectId", "p");
        request.put("stagingUrl", "https://app.test");
        request.put("repositories", List.of(repository));
        request.put("testAccounts", List.of());
        request.put("features", List.of(Map.of("title", "Login", "priority", "HIGH")));
        request.put("departmentMappings", List.of(
                Map.of("category", "DESIGN", "defaultZohoUserId", "u", "fallbackZohoUserId", "u"),
                Map.of("category", "FRONTEND", "defaultZohoUserId", "u", "fallbackZohoUserId", "u"),
                Map.of("category", "BACKEND", "defaultZohoUserId", "u", "fallbackZohoUserId", "u"),
                Map.of("category", "SECURITY", "defaultZohoUserId", "u", "fallbackZohoUserId", "u", "securityLeadZohoUserId", "u")));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/projects")
                .cookie(login(Role.ADMIN), new Cookie("XSRF-TOKEN", "t"))
                .header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private void stubInstallationToken() {
        GITHUB.stubFor(post(urlEqualTo("/app/installations/" + installation + "/access_tokens")).willReturn(okJson(TOKEN_JSON)));
    }

    private Cookie login(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return new Cookie("GK_TOKEN", tokens.issue(users.save(user).getId()));
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The format GitHub issues. Java only exports PKCS#8, whose 26-byte header is removed here. */
    private static String pkcs1Pem() {
        byte[] pkcs8 = KEYS.getPrivate().getEncoded();
        byte[] pkcs1 = java.util.Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        return "-----BEGIN RSA PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }
}
