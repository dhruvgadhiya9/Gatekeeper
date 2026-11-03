package com.gatekeeper.qaplatform.integration.zoho;

import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.ACCESS_TOKEN;
import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.CLIENT_SECRET;
import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.REFRESH_TOKEN;
import static com.gatekeeper.qaplatform.integration.zoho.ZohoTestSupport.ZOHO;
import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.IntegrationTestBase;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.IssueChanges;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.NewIssue;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(OutputCaptureExtension.class)
class ZohoClientTest extends IntegrationTestBase {

    private static final String ISSUES = "/api/v3/portal/1/projects/2/issues";

    @Autowired private ZohoClient client;
    @Autowired private ZohoConnectionService connection;
    @Autowired private ZohoConnectionRepository connections;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    static void zoho(DynamicPropertyRegistry registry) {
        ZohoTestSupport.register(registry);
    }

    @BeforeEach
    void connected() {
        ZOHO.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        ZohoTestSupport.stubTokens();
        connection.disconnect();
        ZOHO.stubFor(post(urlEqualTo("/oauth/v2/token/revoke")).willReturn(okJson("{}")));
        connection.connect("code", null, "admin@example.test");
        ZOHO.resetRequests();
    }

    @Test
    void listsPortalsWithTheCachedToken() {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals"))
                .willReturn(okJson("{\"portals\":[{\"id\":\"1\",\"name\":\"Acme\"}]}")));

        assertThat(client.listPortals()).singleElement().satisfies(portal -> {
            assertThat(portal.id()).isEqualTo("1");
            assertThat(portal.name()).isEqualTo("Acme");
        });

        ZOHO.verify(getRequestedFor(urlPathEqualTo("/api/v3.1/portals"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN)));
        ZOHO.verify(0, postRequestedFor(urlEqualTo("/oauth/v2/token")));
    }

    @Test
    void listsProjectsAcrossPagesFromABareArray() {
        List<String> fullPage = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fullPage.add("{\"id\":\"" + i + "\",\"name\":\"P" + i + "\"}");
        }
        ZOHO.stubFor(get(urlEqualTo("/api/v3/portal/1/projects?page=1&per_page=200"))
                .willReturn(okJson("[" + String.join(",", fullPage) + "]")));
        ZOHO.stubFor(get(urlEqualTo("/api/v3/portal/1/projects?page=2&per_page=200"))
                .willReturn(okJson("[{\"id\":\"999\",\"name\":\"Last\"}]")));

        var projects = client.listProjects("1");

        assertThat(projects).hasSize(201);
        assertThat(projects.getLast().name()).isEqualTo("Last");
    }

    @Test
    void readsBothUserIds() {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3/portal/1/projects/2/users")).willReturn(okJson("""
                {"users":[
                  {"id":"57","zpuid":"238","full_name":"Nolan B","email":"n@x.test"},
                  {"id":"58","display_name":"No Zpuid","email":"z@x.test"}
                ]}
                """)));

        var users = client.listProjectUsers("1", "2");

        assertThat(users.get(0).id()).isEqualTo("57");
        assertThat(users.get(0).zpuid()).isEqualTo("238");
        assertThat(users.get(0).name()).isEqualTo("Nolan B");
        assertThat(users.get(1).zpuid()).isEqualTo("58");
        assertThat(users.get(1).name()).isEqualTo("No Zpuid");
    }

    @Test
    void createsIssueWithDocumentedPayload() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson("{\"id\":\"900\",\"name\":\"Bug\"}")));

        String id = client.createIssue("1", "2", new NewIssue("Bug", "Steps", "238", "11", "22", null));

        assertThat(id).isEqualTo("900");
        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES)).withRequestBody(equalToJson("""
                {"name":"Bug","description":"Steps","assignee":{"zpuid":"238"},
                 "severity":{"id":"11"},"classification":{"id":"22"}}
                """)));
    }

    @Test
    void optionalIssueFieldsAreLeftOut() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[{\"id\":901}]}")));

        assertThat(client.createIssue("1", "2", new NewIssue("Bug", null, null, null, null, null))).isEqualTo("901");

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES)).withRequestBody(equalToJson("{\"name\":\"Bug\"}")));
    }

    @Test
    void createIsNeverRepeatedHere() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES)).willReturn(serverError()));

        assertThatThrownBy(() -> client.createIssue("1", "2", new NewIssue("Bug", null, null, null, null, null)))
                .isInstanceOf(HttpServerErrorException.class);

        ZOHO.verify(1, postRequestedFor(urlEqualTo(ISSUES)));
    }

    @Test
    void updatesOnlyTheGivenFields() {
        ZOHO.stubFor(patch(urlEqualTo(ISSUES + "/900")).willReturn(okJson("{}")));

        client.updateIssue("1", "2", "900", new IssueChanges(null, "238", null, null));

        ZOHO.verify(patchRequestedFor(urlEqualTo(ISSUES + "/900"))
                .withRequestBody(equalToJson("{\"assignee\":{\"zpuid\":\"238\"}}")));
    }

    @Test
    void addsComment() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/comments")).willReturn(okJson("{}")));

        client.addComment("1", "2", "900", "Seen again");

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES + "/900/comments"))
                .withRequestBody(equalToJson("{\"comment\":\"Seen again\"}")));
    }

    @Test
    void commentIsRepeatedOnRateLimitButNotOnAnUnclearFailure() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/comments"))
                .inScenario("limit").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("ok"));
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/comments"))
                .inScenario("limit").whenScenarioStateIs("ok").willReturn(okJson("{}")));
        client.addComment("1", "2", "900", "Once");
        ZOHO.verify(2, postRequestedFor(urlEqualTo(ISSUES + "/900/comments")));

        ZOHO.resetAll();
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/comments")).willReturn(serverError()));
        assertThatThrownBy(() -> client.addComment("1", "2", "900", "Twice"))
                .isInstanceOf(HttpServerErrorException.class);
        ZOHO.verify(1, postRequestedFor(urlEqualTo(ISSUES + "/900/comments")));
    }

    @Test
    void addsFollowersByTheirId() {
        ZOHO.stubFor(post(urlEqualTo(ISSUES + "/900/followers")).willReturn(okJson("{}")));

        client.addFollowers("1", "2", "900", List.of("57"));

        ZOHO.verify(postRequestedFor(urlEqualTo(ISSUES + "/900/followers"))
                .withRequestBody(equalToJson("{\"follower_ids\":[\"57\"]}")));
    }

    @Test
    void uploadsAFileThenAssociatesIt() {
        ZOHO.stubFor(post(urlEqualTo("/api/v3/portal/1/attachments"))
                .willReturn(okJson("{\"attachment\":[{\"attachment_id\":\"555\",\"name\":\"s.png\"}]}")));
        ZOHO.stubFor(post(urlPathEqualTo(ISSUES + "/900/attachments")).willReturn(okJson("{}")));

        String id = client.uploadAttachment("1", "s.png", "image/png", new byte[] {1, 2, 3});
        client.associateAttachments("1", "2", "900", List.of(id));

        assertThat(id).isEqualTo("555");
        ZOHO.verify(postRequestedFor(urlEqualTo("/api/v3/portal/1/attachments"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart().withName("upload_file").withHeader("Content-Type", equalTo("image/png")).build()));
        ZOHO.verify(postRequestedFor(urlPathEqualTo(ISSUES + "/900/attachments"))
                .withQueryParam("attachment_ids", equalTo("[\"555\"]")));
    }

    @Test
    void findsAnIssueByNameAndMarker() {
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES))
                .withQueryParam("filter", matching(".*Checkout fails.*"))
                .willReturn(okJson("""
                        {"issues":[{"id":"1","name":"Checkout fails badly"},{"id":"2","name":"Checkout fails"},{"id":"3","name":"Checkout fails"}]}
                        """)));
        ZOHO.stubFor(get(urlEqualTo(ISSUES + "/2")).willReturn(okJson("{\"id\":\"2\",\"description\":\"Other marker\"}")));
        ZOHO.stubFor(get(urlEqualTo(ISSUES + "/3")).willReturn(okJson("{\"id\":\"3\",\"description\":\"text fp-1\"}")));

        assertThat(client.findIssue("1", "2", "Checkout fails", "fp-1")).contains("3");
        ZOHO.verify(0, getRequestedFor(urlEqualTo(ISSUES + "/1")));
    }

    @Test
    void searchFallsBackWhenZohoRejectsTheFilter() {
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).withQueryParam("filter", matching(".*")).willReturn(badRequest()));
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).withQueryParam("filter", absent())
                .willReturn(okJson("{\"issues\":[{\"id\":\"7\",\"name\":\"Bug\"}]}")));
        ZOHO.stubFor(get(urlEqualTo(ISSUES + "/7")).willReturn(okJson("{\"description\":\"fp-1\"}")));

        assertThat(client.findIssue("1", "2", "Bug", "fp-1")).contains("7");
    }

    @Test
    void searchFindingNothingIsEmpty() {
        ZOHO.stubFor(get(urlPathEqualTo(ISSUES)).willReturn(okJson("{\"issues\":[]}")));

        assertThat(client.findIssue("1", "2", "Bug", "fp-1")).isEmpty();
    }

    @Test
    void rejectedAccessTokenIsRenewedOnce() {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals"))
                .inScenario("expired").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(unauthorized()).willSetStateTo("renewed"));
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals"))
                .inScenario("expired").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"portals\":[]}")));

        assertThat(client.listPortals()).isEmpty();

        ZOHO.verify(1, postRequestedFor(urlEqualTo("/oauth/v2/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=" + REFRESH_TOKEN)));
    }

    @Test
    void readsAreRetriedOnTemporaryFailuresOnly() {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals"))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("ok"));
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals"))
                .inScenario("flaky").whenScenarioStateIs("ok").willReturn(okJson("{\"portals\":[]}")));
        assertThat(client.listPortals()).isEmpty();
        ZOHO.verify(2, getRequestedFor(urlPathEqualTo("/api/v3.1/portals")));

        ZOHO.resetAll();
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals")).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client.listPortals()).isInstanceOf(HttpClientErrorException.Forbidden.class);
        ZOHO.verify(1, getRequestedFor(urlPathEqualTo("/api/v3.1/portals")));
    }

    @Test
    void secretsNeverReachTheLogs(CapturedOutput output) {
        ZOHO.stubFor(get(urlPathEqualTo("/api/v3.1/portals")).willReturn(serverError().withBody("boom")));

        assertThatThrownBy(() -> client.listPortals()).isInstanceOf(HttpServerErrorException.class);

        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN, REFRESH_TOKEN, CLIENT_SECRET);
    }
}
