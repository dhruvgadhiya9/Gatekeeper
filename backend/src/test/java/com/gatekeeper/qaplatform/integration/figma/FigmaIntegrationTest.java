package com.gatekeeper.qaplatform.integration.figma;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.InMemoryObjectStorage;
import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import com.gatekeeper.qaplatform.project.DesignSource;
import com.gatekeeper.qaplatform.project.DesignSourceRepository;
import com.gatekeeper.qaplatform.project.DesignUploadRepository;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@ExtendWith(OutputCaptureExtension.class)
class FigmaIntegrationTest extends IntegrationTestBase {

    private static final String TOKEN = "figd_secret_project_token";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};
    private static final WireMockServer FIGMA = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String FILE = """
            {"document":{"children":[
              {"name":"Web","children":[
                {"id":"1:2","name":"Home","type":"FRAME"},
                {"id":"1:3","name":"Note","type":"TEXT"},
                {"id":"1:4","name":"Cart/Empty","type":"FRAME"}]},
              {"name":"Mobile","children":[{"id":"2:1","name":"Home","type":"FRAME"}]}
            ]}}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InMemoryObjectStorage storage;
    @Autowired private ProjectRepository projects;
    @Autowired private DesignSourceRepository designSources;
    @Autowired private DesignUploadRepository uploads;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private CircuitBreakerRegistry circuitBreakers;
    @Autowired private FigmaClient client;

    private UUID projectId;

    @DynamicPropertySource
    static void figmaProperties(DynamicPropertyRegistry registry) {
        FIGMA.start();
        registry.add("app.figma.api-url", FIGMA::baseUrl);
        registry.add("resilience4j.retry.configs.external.wait-duration", () -> "1ms");
    }

    @AfterAll
    static void stop() {
        FIGMA.stop();
    }

    @BeforeEach
    void setUp() {
        FIGMA.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        storage.objects.clear();
        projectId = saveProject(DesignSource.Type.FIGMA);
        FIGMA.stubFor(get(urlPathEqualTo("/v1/files/AbC123")).willReturn(okJson(FILE)));
    }

    @Test
    void listsOnlyFramesUsingTheProjectToken() throws Exception {
        frames(Role.QA_LEAD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("1:2"))
                .andExpect(jsonPath("$[0].page").value("Web"))
                .andExpect(jsonPath("$[2].page").value("Mobile"));

        FIGMA.verify(getRequestedFor(urlPathEqualTo("/v1/files/AbC123"))
                .withHeader("X-Figma-Token", equalTo(TOKEN))
                .withQueryParam("depth", equalTo("2")));
    }

    @Test
    void exportsFramesAsPngIntoStorageWithoutSendingTheTokenToTheImageHost() throws Exception {
        stubRender("{\"images\":{\"1:2\":\"" + FIGMA.baseUrl() + "/img/a.png\"}}");
        FIGMA.stubFor(get(urlPathEqualTo("/img/a.png")).willReturn(aResponse().withStatus(200).withBody(PNG)));

        export(Role.QA_LEAD, List.of("1:2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exported[0].fileName").value("Web - Home.png"))
                .andExpect(jsonPath("$.exported[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.failed.length()").value(0));

        assertThat(storage.objects.values()).singleElement()
                .satisfies(object -> assertThat(object.content()).isEqualTo(PNG));
        FIGMA.verify(getRequestedFor(urlPathEqualTo("/v1/images/AbC123"))
                .withQueryParam("ids", equalTo("1:2"))
                .withQueryParam("format", equalTo("png")));
        FIGMA.verify(getRequestedFor(urlPathEqualTo("/img/a.png")).withHeader("X-Figma-Token", absent()));
    }

    @Test
    void framesThatFailToRenderAreReportedAndOthersStillExport() throws Exception {
        stubRender("{\"images\":{\"1:2\":\"" + FIGMA.baseUrl() + "/img/a.png\",\"1:4\":null}}");
        FIGMA.stubFor(get(urlPathEqualTo("/img/a.png")).willReturn(aResponse().withStatus(200).withBody(PNG)));

        export(Role.ADMIN, List.of("1:2", "1:4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exported.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value("1:4"))
                .andExpect(jsonPath("$.failed[0].name").value("Cart/Empty"));
    }

    @Test
    void aDownloadThatIsNotAPngIsReportedNotStored() throws Exception {
        stubRender("{\"images\":{\"1:2\":\"" + FIGMA.baseUrl() + "/img/a.png\"}}");
        FIGMA.stubFor(get(urlPathEqualTo("/img/a.png")).willReturn(aResponse().withStatus(200).withBody("<html>")));

        export(Role.ADMIN, List.of("1:2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].id").value("1:2"));

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void exportingAFrameAgainReplacesItsImage() throws Exception {
        stubRender("{\"images\":{\"1:2\":\"" + FIGMA.baseUrl() + "/img/a.png\"}}");
        FIGMA.stubFor(get(urlPathEqualTo("/img/a.png")).willReturn(aResponse().withStatus(200).withBody(PNG)));

        export(Role.ADMIN, List.of("1:2")).andExpect(status().isOk());
        String firstKey = storage.objects.keySet().iterator().next();
        export(Role.ADMIN, List.of("1:2")).andExpect(status().isOk());

        assertThat(storage.objects).hasSize(1).doesNotContainKey(firstKey);
        DesignSource source = designSources.findByProjectId(projectId).getFirst();
        assertThat(uploads.countByDesignSourceId(source.getId())).isEqualTo(1);
    }

    @Test
    void framesNotInTheFileAreRejectedBeforeRendering() throws Exception {
        export(Role.ADMIN, List.of("9:9")).andExpect(status().isBadRequest());

        FIGMA.verify(0, getRequestedFor(urlPathEqualTo("/v1/images/AbC123")));
    }

    @Test
    void malformedAndEmptyFrameListsAreRejected() throws Exception {
        export(Role.ADMIN, List.of("1:2&ids=x")).andExpect(status().isBadRequest());
        export(Role.ADMIN, List.of()).andExpect(status().isBadRequest());
    }

    @Test
    void imageAddressesFromFigmaAreChecked() {
        assertThatThrownBy(() -> client.downloadImage("http://169.254.169.254/latest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.downloadImage("https://10.0.0.1/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.downloadImage("https://localhost/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.downloadImage("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnsafeImageAddressFailsThatFrameOnly() throws Exception {
        stubRender("{\"images\":{\"1:2\":\"http://169.254.169.254/latest\"}}");

        export(Role.ADMIN, List.of("1:2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].id").value("1:2"));

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void rejectedTokenAndMissingFileGiveUsefulMessages() throws Exception {
        FIGMA.stubFor(get(urlPathEqualTo("/v1/files/AbC123")).willReturn(aResponse().withStatus(403)));
        frames(Role.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Figma rejected the stored token. Edit the project and enter a new one."));

        FIGMA.stubFor(get(urlPathEqualTo("/v1/files/AbC123")).willReturn(aResponse().withStatus(404)));
        frames(Role.ADMIN).andExpect(status().isConflict());
    }

    @Test
    void temporaryFailuresAreRetriedAndThenReported() throws Exception {
        FIGMA.stubFor(get(urlPathEqualTo("/v1/files/AbC123")).willReturn(serverError()));

        frames(Role.ADMIN).andExpect(status().isBadGateway());

        FIGMA.verify(3, getRequestedFor(urlPathEqualTo("/v1/files/AbC123")));
    }

    @Test
    void uploadProjectsCantUseFigma() throws Exception {
        projectId = saveProject(DesignSource.Type.UPLOAD);

        frames(Role.ADMIN).andExpect(status().isConflict());
    }

    @Test
    void viewersCantCallFigma() throws Exception {
        frames(Role.VIEWER).andExpect(status().isForbidden());
        export(Role.VIEWER, List.of("1:2")).andExpect(status().isForbidden());
    }

    @Test
    void tokenNeverReachesTheLogsOrResponses(CapturedOutput output) throws Exception {
        FIGMA.stubFor(get(urlPathEqualTo("/v1/files/AbC123")).willReturn(serverError().withBody("oops")));

        String body = frames(Role.ADMIN).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private void stubRender(String json) {
        FIGMA.stubFor(get(urlPathEqualTo("/v1/images/AbC123")).willReturn(okJson(json)));
    }

    private ResultActions frames(Role role) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/projects/" + projectId + "/figma/frames").cookie(login(role)));
    }

    private ResultActions export(Role role, List<String> ids) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/projects/" + projectId + "/figma/exports")
                .cookie(login(role), new Cookie("XSRF-TOKEN", "t"))
                .header("X-XSRF-TOKEN", "t")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("frameIds", ids))));
    }

    private UUID saveProject(DesignSource.Type type) {
        UUID id = projects.save(new Project("Demo", "https://app.test", "qa@example.test")).getId();
        DesignSource source = new DesignSource(id, type);
        if (type == DesignSource.Type.FIGMA) {
            source.setFigmaFileKey("AbC123");
            source.setToken(TOKEN);
        }
        designSources.save(source);
        return id;
    }

    private Cookie login(Role role) {
        String id = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://idp.example.test", id, id + "@example.test");
        user.setRole(role);
        return new Cookie("GK_TOKEN", tokens.issue(users.save(user).getId()));
    }
}
