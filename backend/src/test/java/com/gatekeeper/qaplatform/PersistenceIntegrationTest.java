package com.gatekeeper.qaplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.audit.AuditEvent;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.finding.FindingRepository;
import com.gatekeeper.qaplatform.project.DesignSource;
import com.gatekeeper.qaplatform.project.DesignSourceRepository;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.TestAccount;
import com.gatekeeper.qaplatform.project.TestAccountRepository;
import com.gatekeeper.qaplatform.report.Report;
import com.gatekeeper.qaplatform.report.ReportRepository;
import com.gatekeeper.qaplatform.routing.UserMapping;
import com.gatekeeper.qaplatform.routing.UserMappingRepository;
import com.gatekeeper.qaplatform.run.TestRun;
import com.gatekeeper.qaplatform.run.TestRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application against real PostgreSQL. Startup itself proves that the Flyway
 * migration matches every JPA entity, because Hibernate runs with {@code ddl-auto=validate}.
 */
@SpringBootTest(properties = "app.encryption.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Testcontainers
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ProjectRepository projects;
    @Autowired private TestAccountRepository testAccounts;
    @Autowired private DesignSourceRepository designSources;
    @Autowired private TestRunRepository runs;
    @Autowired private FindingRepository findings;
    @Autowired private ReportRepository reports;
    @Autowired private UserMappingRepository userMappings;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @Test
    void testAccountPasswordIsEncryptedInTheDatabase() {
        Project project = saveProject();
        TestAccount saved = testAccounts.save(new TestAccount(
                project.getId(), "admin", "qa@example.test", "plain-password", "https://staging.example.test/login"));

        String stored = jdbc.queryForObject(
                "select encrypted_password from test_account where id = ?", String.class, saved.getId());

        assertThat(stored).isNotEqualTo("plain-password").doesNotContain("plain-password");
        assertThat(testAccounts.findById(saved.getId()).orElseThrow().getPassword()).isEqualTo("plain-password");
    }

    @Test
    void designTokenIsEncryptedInTheDatabase() {
        DesignSource source = new DesignSource(saveProject().getId(), DesignSource.Type.FIGMA);
        source.setToken("figma-token");
        DesignSource saved = designSources.save(source);

        String stored = jdbc.queryForObject(
                "select encrypted_token from design_source where id = ?", String.class, saved.getId());

        assertThat(stored).isNotEqualTo("figma-token");
        assertThat(designSources.findById(saved.getId()).orElseThrow().getToken()).isEqualTo("figma-token");
    }

    @Test
    void findingKeepsArrayColumns() {
        TestRun run = runs.save(new TestRun(saveProject().getId()));
        Finding finding = new Finding(run.getId(), Finding.Category.UNKNOWN, Finding.Severity.HIGH, "Login fails");
        finding.setStepsToReproduce(List.of("Open login", "Submit"));
        finding.setEvidenceKeys(List.of("runs/1/screenshot.png"));

        Finding loaded = findings.findById(findings.save(finding).getId()).orElseThrow();

        assertThat(loaded.getStepsToReproduce()).containsExactly("Open login", "Submit");
        assertThat(loaded.getEvidenceKeys()).containsExactly("runs/1/screenshot.png");
        assertThat(loaded.getStatus()).isEqualTo(Finding.Status.NEW);
    }

    @Test
    void jsonColumnsRoundTrip() {
        TestRun run = runs.save(new TestRun(saveProject().getId()));
        Report report = new Report(run.getId());
        report.setSummaryJson("{\"passed\": 3}");
        Report loaded = reports.findById(reports.save(report).getId()).orElseThrow();

        AuditEvent event = auditEvents.save(
                new AuditEvent("qa@example.test", "RUN_START", run.getId().toString(), "{\"a\": 1}", Instant.now()));

        assertThat(loaded.getSummaryJson()).contains("\"passed\"");
        assertThat(auditEvents.findById(event.getId()).orElseThrow().getDetailsJson()).contains("\"a\"");
    }

    @Test
    void childRowsRequireAnExistingProject() {
        TestRun orphan = new TestRun(UUID.randomUUID());

        assertThatThrownBy(() -> runs.saveAndFlush(orphan)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void githubUsernamesAreUnique() {
        String username = "user-" + UUID.randomUUID();
        userMappings.saveAndFlush(new UserMapping(username, "zoho-1"));

        assertThatThrownBy(() -> userMappings.saveAndFlush(new UserMapping(username, "zoho-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocsAreServed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/docs"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.info.title").value("Gatekeeper QA Platform API"));
    }

    private Project saveProject() {
        return projects.save(new Project("Demo", "https://staging.example.test", "qa@example.test"));
    }
}
