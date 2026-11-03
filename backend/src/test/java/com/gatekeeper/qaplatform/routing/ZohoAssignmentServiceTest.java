package com.gatekeeper.qaplatform.routing;

import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.ZOHO;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.audit.AuditEvent;
import com.gatekeeper.qaplatform.audit.AuditEventRepository;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.integration.zoho.ZohoConnectionService;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.IssueChanges;
import com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.routing.ZohoAssignmentService.FileIssue;
import com.gatekeeper.qaplatform.routing.ZohoAssignmentService.FiledIssue;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class ZohoAssignmentServiceTest extends IntegrationTestBase {

    private static final String ISSUES = "/api/v3/portal/1/projects/2/issues";
    private static final String CREATED = "{\"id\":\"900\"}";

    @Autowired private ZohoAssignmentService service;
    @Autowired private ZohoConnectionService connection;
    @Autowired private ProjectRepository projects;
    @Autowired private ZohoIssueLinkRepository links;
    @Autowired private ZohoFieldMappingRepository fieldMappings;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    private UUID projectId;
    private String fingerprint;

    @DynamicPropertySource
    static void zoho(DynamicPropertyRegistry registry) {
        ZohoTestSupport.register(registry);
    }

    @BeforeEach
    void setUp() {
        ZOHO.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        ZohoTestSupport.stubTokens();
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token/revoke")).willReturn(okJson("{}")));
        connection.disconnect();
        connection.connect("code", null, "admin@example.test");
        fieldMappings.deleteAll();

        Project project = new Project("Shop", "https://app.test", "qa@example.test");
        project.setZohoPortalId("1");
        project.setZohoProjectId("2");
        projectId = projects.save(project).getId();
        fingerprint = "fp" + UUID.randomUUID().toString().replace("-", "");
        ZOHO.resetRequests();
    }

    @Test
    void filesAnIssueWithAssigneeAndFingerprintMarker() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson(CREATED)));

        FiledIssue filed = service.fileIssue(command());

        assertThat(filed.zohoIssueId()).isEqualTo("900");
        assertThat(filed.created()).isTrue();
        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES))
                .withRequestBody(matchingJsonPath("$.name", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Checkout fails")))
                .withRequestBody(matchingJsonPath("$.assignee.zpuid", com.github.tomakehurst.wiremock.client.WireMock.equalTo("238")))
                .withRequestBody(containing("Gatekeeper-Fingerprint: " + fingerprint)));
        var link = links.findByProjectIdAndFingerprint(projectId, fingerprint).orElseThrow();
        assertThat(link.getStatus()).isEqualTo(ZohoIssueLink.Status.CREATED);
        assertThat(link.getZohoIssueId()).isEqualTo("900");
    }

    @Test
    void fingerprintIsSavedBeforeZohoIsCalled() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(aResponse().withStatus(500)));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[]}")));

        assertThatThrownBy(() -> service.fileIssue(command())).isInstanceOf(HttpServerErrorException.class);

        assertThat(links.findByProjectIdAndFingerprint(projectId, fingerprint)).isPresent();
    }

    @Test
    void aSearchErrorAfterAnUnclearCreateKeepsTheFingerprint() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(serverError()));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> service.fileIssue(command())).isInstanceOf(HttpClientErrorException.Forbidden.class);

        assertThat(links.findByProjectIdAndFingerprint(projectId, fingerprint).orElseThrow().getStatus())
                .isEqualTo(ZohoIssueLink.Status.UNCERTAIN);
    }

    @Test
    void filingTheSameFingerprintAgainDoesNotCallZoho() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson(CREATED)));
        service.fileIssue(command());
        ZOHO.resetRequests();

        FiledIssue second = service.fileIssue(command());

        assertThat(second.zohoIssueId()).isEqualTo("900");
        assertThat(second.created()).isFalse();
        ZOHO.verify(0, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void afterAnUnclearFailureTheRetryFindsTheIssueInsteadOfCreatingASecond() {
        // Zoho created the issue, but the response was lost.
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(serverError()));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[{\"id\":\"777\",\"name\":\"Checkout fails\"}]}")));
        ZOHO.stubFor(get(urlEqualTo(ISSUES + "/777"))
                .willReturn(okJson("{\"description\":\"Gatekeeper-Fingerprint: " + fingerprint + "\"}")));

        FiledIssue filed = service.fileIssue(command());

        assertThat(filed.zohoIssueId()).isEqualTo("777");
        assertThat(filed.created()).isFalse();
        ZOHO.verify(1, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void retryCreatesWhenTheFailedAttemptReallyCreatedNothing() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("ok"));
        ZOHO.stubFor(post(urlEqualTo(ISSUES))
                .inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson(CREATED)));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[]}")));

        assertThat(service.fileIssue(command()).created()).isTrue();

        ZOHO.verify(2, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void aLaterAttemptAfterExhaustedRetriesChecksZohoFirst() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(serverError()));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[]}")));
        assertThatThrownBy(() -> service.fileIssue(command())).isInstanceOf(HttpServerErrorException.class);
        assertThat(links.findByProjectIdAndFingerprint(projectId, fingerprint).orElseThrow().getStatus())
                .isEqualTo(ZohoIssueLink.Status.UNCERTAIN);

        ZOHO.resetAll();
        ZohoTestSupport.stubTokens();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[{\"id\":\"778\",\"name\":\"Checkout fails\"}]}")));
        ZOHO.stubFor(get(urlEqualTo(ISSUES + "/778"))
                .willReturn(okJson("{\"description\":\"Gatekeeper-Fingerprint: " + fingerprint + "\"}")));

        assertThat(service.fileIssue(command()).zohoIssueId()).isEqualTo("778");
        ZOHO.verify(0, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void aRejectedIssueReleasesTheFingerprint() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(badRequest()));

        assertThatThrownBy(() -> service.fileIssue(command())).isInstanceOf(HttpClientErrorException.BadRequest.class);

        assertThat(links.findByProjectIdAndFingerprint(projectId, fingerprint)).isEmpty();
        ZOHO.verify(1, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void anAttemptInProgressBlocksASecondOne() {
        links.saveAndFlush(new ZohoIssueLink(projectId, fingerprint));

        assertThatThrownBy(() -> service.fileIssue(command())).isInstanceOf(ConflictException.class);

        ZOHO.verify(0, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void mappedOptionsAreSentAsIds() {
        fieldMappings.save(new ZohoFieldMapping("1", ZohoFieldMapping.Kind.SEVERITY, "HIGH", "11"));
        fieldMappings.save(new ZohoFieldMapping("1", ZohoFieldMapping.Kind.CLASSIFICATION, "BACKEND", "22"));
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson(CREATED)));

        service.fileIssue(command());

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES))
                .withRequestBody(matchingJsonPath("$.severity.id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("11")))
                .withRequestBody(matchingJsonPath("$.classification.id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("22")))
                .withRequestBody(notContaining("Severity: HIGH")));
    }

    @Test
    void unmappedOptionsGoInTheDescriptionSoTheyAreNotLost() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson(CREATED)));

        service.fileIssue(command());

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES))
                .withRequestBody(notContaining("\"severity\""))
                .withRequestBody(containing("Severity: HIGH"))
                .withRequestBody(containing("Category: BACKEND")));
    }

    @Test
    void invalidCommandsNeverReachZoho() {
        assertThatThrownBy(() -> service.fileIssue(new FileIssue(projectId, "short", "T", null, null, null, null)))
                .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.fileIssue(new FileIssue(projectId, fingerprint, " ", null, null, null, null)))
                .isInstanceOf(RequestValidationException.class);
        ZOHO.verify(0, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void followersAreTranslatedFromZpuidToId() {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3/portal/1/projects/2/users"))
                .willReturn(okJson("{\"users\":[{\"id\":\"57\",\"zpuid\":\"238\",\"full_name\":\"N\"}]}")));
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/followers")).willReturn(okJson("{}")));

        service.addFollowers(projectId, "900", List.of("238"));

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES + "/900/followers"))
                .withRequestBody(equalToJson("{\"follower_ids\":[\"57\"]}")));
        assertThatThrownBy(() -> service.addFollowers(projectId, "900", List.of("unknown")))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void attachesAFileInTwoSteps() {
        ZOHO.stubFor(post(urlEqualTo("/api/v3/portal/1/attachments"))
                .willReturn(okJson("{\"attachment\":[{\"attachment_id\":\"555\"}]}")));
        ZOHO.stubFor(post(urlPathEqualTo(ISSUES + "/900/attachments")).willReturn(okJson("{}")));

        assertThat(service.attachFile(projectId, "900", "s.png", "image/png", new byte[] {1})).isTrue();

        ZOHO.verify(postRequestedFor(urlPathEqualTo(ISSUES + "/900/attachments")));
    }

    @Test
    void everyWriteIsAuditedIncludingFailures() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson(CREATED)));
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/comments")).willReturn(okJson("{}")));
        ZOHO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(urlEqualTo(ISSUES + "/900")).willReturn(okJson("{}")));
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/901/comments")).willReturn(serverError()));

        service.fileIssue(command());
        service.addComment(projectId, "900", "Seen again");
        service.updateIssue(projectId, "900", new IssueChanges(null, "238", null, null));
        assertThatThrownBy(() -> service.addComment(projectId, "901", "x")).isInstanceOf(HttpServerErrorException.class);

        List<AuditEvent> events = auditEvents.findAll();
        assertThat(events).anyMatch(event -> "ZOHO_CREATE_ISSUE".equals(event.getAction())
                && "900".equals(event.getTarget()) && event.getDetailsJson().contains(fingerprint));
        assertThat(events).anyMatch(event -> "ZOHO_COMMENT".equals(event.getAction()) && "900".equals(event.getTarget()));
        assertThat(events).anyMatch(event -> "ZOHO_UPDATE_ISSUE".equals(event.getAction()));
        assertThat(events).anyMatch(event -> "ZOHO_COMMENT".equals(event.getAction())
                && "901".equals(event.getTarget()) && event.getDetailsJson().contains("FAILURE"));
    }

    private FileIssue command() {
        return new FileIssue(
                projectId, fingerprint, "Checkout fails", "Steps", "238", Finding.Severity.HIGH, Finding.Category.BACKEND);
    }
}
