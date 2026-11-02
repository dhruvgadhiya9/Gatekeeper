package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.InMemoryObjectStorage;
import com.gatekeeper.qaplatform.auth.AppUser;
import com.gatekeeper.qaplatform.auth.AppUserRepository;
import com.gatekeeper.qaplatform.auth.JwtTokenService;
import com.gatekeeper.qaplatform.auth.Role;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class DesignUploadIntegrationTest extends IntegrationTestBase {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};

    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryObjectStorage storage;
    @Autowired private ProjectRepository projects;
    @Autowired private DesignSourceRepository designSources;
    @Autowired private AppUserRepository users;
    @Autowired private JwtTokenService tokens;

    private UUID projectId;

    @BeforeEach
    void projectWithUploadedDesign() {
        projectId = saveProject(DesignSource.Type.UPLOAD);
        storage.objects.clear();
    }

    @Test
    void storesImagesAndShowsThemOnTheProject() throws Exception {
        upload(projectId, Role.QA_LEAD, file("home.png", "image/png", PNG), file("cart.jpg", "image/jpeg", JPEG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileName").value("home.png"))
                .andExpect(jsonPath("$[1].contentType").value("image/jpeg"));

        assertThat(storage.objects).hasSize(2);
        mockMvc.perform(get("/api/projects/" + projectId).cookie(login(Role.VIEWER)))
                .andExpect(jsonPath("$.design.uploads[0].fileName").value("cart.jpg"))
                .andExpect(jsonPath("$.design.uploads[0].storageKey").doesNotExist());
    }

    @Test
    void typeComesFromTheBytesNotTheBrowser() throws Exception {
        upload(projectId, Role.ADMIN, file("page.html", "image/png", "<script>x</script>".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("files[0]"));
        assertThat(storage.objects).isEmpty();

        upload(projectId, Role.ADMIN, file("real.txt", "text/html", PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].contentType").value("image/png"));
    }

    @Test
    void oneBadFileMeansNoneAreStored() throws Exception {
        upload(projectId, Role.ADMIN, file("good.png", "image/png", PNG), file("bad.png", "image/png", new byte[] {1}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("files[1]"));

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void fileNameCantAffectTheStorageKey() throws Exception {
        upload(projectId, Role.ADMIN, file("../../../etc/passwd.png", "image/png", PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileName").value("passwd.png"));

        assertThat(storage.objects.keySet()).singleElement().satisfies(key -> {
            assertThat(key).startsWith("projects/" + projectId + "/design/").endsWith(".png");
            assertThat(key).doesNotContain("..", "passwd");
        });
    }

    @Test
    void projectHasAFileLimit() throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[51];
        java.util.Arrays.fill(files, file("a.png", "image/png", PNG));

        upload(projectId, Role.ADMIN, files).andExpect(status().isBadRequest());

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void figmaProjectCantTakeUploads() throws Exception {
        upload(saveProject(DesignSource.Type.FIGMA), Role.ADMIN, file("a.png", "image/png", PNG))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        upload(UUID.randomUUID(), Role.ADMIN, file("a.png", "image/png", PNG)).andExpect(status().isNotFound());
    }

    @Test
    void viewerCantUpload() throws Exception {
        upload(projectId, Role.VIEWER, file("a.png", "image/png", PNG)).andExpect(status().isForbidden());
    }

    @Test
    void cleansFileNames() {
        assertThat(DesignUploadService.cleanFileName("C:\\Users\\me\\home.png")).isEqualTo("home.png");
        assertThat(DesignUploadService.cleanFileName("a\u0000b\n.png")).isEqualTo("ab.png");
        assertThat(DesignUploadService.cleanFileName(null)).isEqualTo("design");
        assertThat(DesignUploadService.cleanFileName("x".repeat(300) + ".png")).hasSize(255).endsWith(".png");
    }

    private ResultActions upload(UUID id, Role role, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/projects/" + id + "/design-uploads");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return mockMvc.perform(request
                .cookie(login(role), new Cookie("XSRF-TOKEN", "t"))
                .header("X-XSRF-TOKEN", "t"));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("files", name, contentType, content);
    }

    private UUID saveProject(DesignSource.Type type) {
        UUID id = projects.save(new Project("Demo", "https://app.test", "qa@example.test")).getId();
        DesignSource source = new DesignSource(id, type);
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
