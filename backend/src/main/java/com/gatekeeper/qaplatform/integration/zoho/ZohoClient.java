package com.gatekeeper.qaplatform.integration.zoho;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.qaplatform.config.ZohoProperties;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.Issue;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.IssueChanges;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.NewIssue;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.Portal;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.Project;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.User;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Typed access to Zoho Projects. Other code must make writes through ZohoAssignmentService, which
 * adds idempotency and auditing.
 *
 * <p>Retries match what's safe. Reads and PATCH repeat freely. {@link #createIssue} isn't retried
 * here, because the caller must check first whether an unclear attempt actually worked. Comments
 * and uploads repeat only on a 429, when Zoho is known not to have processed them.
 */
@Component
public class ZohoClient {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 25;
    /** Limits the unfiltered fallback when looking for an issue; the newest come first. */
    private static final int MAX_SEARCH_PAGES = 5;

    private final RestClient rest;
    private final ZohoConnectionService connection;
    private final ObjectMapper objectMapper;

    public ZohoClient(
            RestClient.Builder builder,
            ZohoProperties properties,
            ZohoConnectionService connection,
            ObjectMapper objectMapper) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.rest = builder.clone().baseUrl(properties.apiBase()).requestFactory(requestFactory).build();
        this.connection = connection;
        this.objectMapper = objectMapper;
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public List<Portal> listPortals() {
        JsonNode response = authorized(token -> rest.get()
                .uri(ZohoEndpoints.PORTALS)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class));
        List<Portal> portals = new ArrayList<>();
        for (JsonNode node : items(response, "portals")) {
            portals.add(new Portal(node.path("id").asText(), node.path("name").asText()));
        }
        return portals;
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public List<Project> listProjects(String portalId) {
        return fetchPages(MAX_PAGES, "projects",
                page -> authorized(token -> rest.get()
                        .uri(ZohoEndpoints.PROJECTS, portalId, page, PAGE_SIZE)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(JsonNode.class)),
                node -> new Project(node.path("id").asText(), node.path("name").asText()));
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public List<User> listProjectUsers(String portalId, String projectId) {
        return fetchPages(MAX_PAGES, "users",
                page -> authorized(token -> rest.get()
                        .uri(ZohoEndpoints.PROJECT_USERS, portalId, projectId, page, PAGE_SIZE)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(JsonNode.class)),
                ZohoClient::toUser);
    }

    /**
     * Finds an issue by its exact name and a marker in its description. The name filter comes first;
     * the filter's use on this endpoint isn't documented, so a rejected filter falls back to reading
     * the newest issues.
     */
    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public Optional<String> findIssue(String portalId, String projectId, String name, String marker) {
        for (Issue candidate : searchByName(portalId, projectId, name)) {
            if (name.equals(candidate.name()) && issueDescription(portalId, projectId, candidate.id()).contains(marker)) {
                return Optional.of(candidate.id());
            }
        }
        return Optional.empty();
    }

    @CircuitBreaker(name = "zoho")
    public String createIssue(String portalId, String projectId, NewIssue issue) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", issue.name());
        putIfSet(body, "description", issue.description());
        putIdIfSet(body, "assignee", "zpuid", issue.assigneeZpuid());
        putIdIfSet(body, "severity", "id", issue.severityId());
        putIdIfSet(body, "classification", "id", issue.classificationId());
        putIdIfSet(body, "module", "id", issue.moduleId());
        JsonNode response = authorized(token -> rest.post()
                .uri(ZohoEndpoints.ISSUES, portalId, projectId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        JsonNode created = response.path("id").isMissingNode() && response.path("issues").isArray()
                ? response.path("issues").path(0)
                : response;
        if (!created.path("id").isTextual() && !created.path("id").isNumber()) {
            throw new IllegalStateException("Zoho's create issue response has no issue ID");
        }
        return created.path("id").asText();
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public void updateIssue(String portalId, String projectId, String issueId, IssueChanges changes) {
        Map<String, Object> body = new HashMap<>();
        putIfSet(body, "description", changes.description());
        putIdIfSet(body, "assignee", "zpuid", changes.assigneeZpuid());
        putIdIfSet(body, "severity", "id", changes.severityId());
        putIdIfSet(body, "classification", "id", changes.classificationId());
        authorized(token -> rest.patch()
                .uri(ZohoEndpoints.ISSUE, portalId, projectId, issueId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    @Retry(name = "zoho-rate-limited")
    @CircuitBreaker(name = "zoho")
    public void addComment(String portalId, String projectId, String issueId, String comment) {
        authorized(token -> rest.post()
                .uri(ZohoEndpoints.ISSUE_COMMENTS, portalId, projectId, issueId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("comment", comment))
                .retrieve()
                .toBodilessEntity());
    }

    /** @param followerIds each user's {@code id}, not {@code zpuid}. Adding one twice does no harm. */
    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public void addFollowers(String portalId, String projectId, String issueId, List<String> followerIds) {
        authorized(token -> rest.post()
                .uri(ZohoEndpoints.ISSUE_FOLLOWERS, portalId, projectId, issueId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("follower_ids", followerIds))
                .retrieve()
                .toBodilessEntity());
    }

    /** Returns the attachment's ID, for {@link #associateAttachments}. */
    @Retry(name = "zoho-rate-limited")
    @CircuitBreaker(name = "zoho")
    public String uploadAttachment(String portalId, String fileName, String contentType, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        form.add("upload_file", new org.springframework.http.HttpEntity<>(
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                },
                headers));
        JsonNode response = authorized(token -> rest.post()
                .uri(ZohoEndpoints.ATTACHMENTS, portalId)
                .headers(requestHeaders -> requestHeaders.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(JsonNode.class));
        JsonNode id = response.path("attachment").path(0).path("attachment_id");
        if (id.isMissingNode() || id.isNull()) {
            throw new IllegalStateException("Zoho's upload response has no attachment ID");
        }
        return id.asText();
    }

    @Retry(name = "zoho")
    @CircuitBreaker(name = "zoho")
    public void associateAttachments(String portalId, String projectId, String issueId, List<String> attachmentIds) {
        String ids = toJson(attachmentIds);
        authorized(token -> rest.post()
                .uri(ZohoEndpoints.ISSUE_ATTACHMENTS, portalId, projectId, issueId, ids)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity());
    }

    private List<Issue> searchByName(String portalId, String projectId, String name) {
        String filter = toJson(Map.of(
                "criteria", List.of(Map.of("field_name", "name", "criteria_condition", "contains", "value", List.of(name))),
                "pattern", "1"));
        try {
            return fetchPages(1, "issues",
                    page -> authorized(token -> rest.get()
                            .uri(ZohoEndpoints.ISSUES_FILTERED, portalId, projectId, page, PAGE_SIZE, filter)
                            .headers(headers -> headers.setBearerAuth(token))
                            .retrieve()
                            .body(JsonNode.class)),
                    ZohoClient::toIssue);
        } catch (HttpClientErrorException.BadRequest filterRejected) {
            return fetchPages(MAX_SEARCH_PAGES, "issues",
                    page -> authorized(token -> rest.get()
                            .uri(ZohoEndpoints.ISSUES_PAGE, portalId, projectId, page, PAGE_SIZE)
                            .headers(headers -> headers.setBearerAuth(token))
                            .retrieve()
                            .body(JsonNode.class)),
                    ZohoClient::toIssue);
        }
    }

    private String issueDescription(String portalId, String projectId, String issueId) {
        JsonNode issue = authorized(token -> rest.get()
                .uri(ZohoEndpoints.ISSUE, portalId, projectId, issueId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class));
        return issue.path("description").asText("");
    }

    /** Access tokens can be revoked early, so a rejected token gets one renewal and one more try. */
    private <T> T authorized(Function<String, T> call) {
        String token = connection.accessToken();
        try {
            return call.apply(token);
        } catch (HttpClientErrorException.Unauthorized rejected) {
            connection.invalidate(token);
            return call.apply(connection.accessToken());
        }
    }

    private static <T> List<T> fetchPages(
            int maxPages, String key, Function<Integer, JsonNode> fetch, Function<JsonNode, T> mapper) {
        List<T> results = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            int count = 0;
            for (JsonNode node : items(fetch.apply(page), key)) {
                results.add(mapper.apply(node));
                count++;
            }
            if (count < PAGE_SIZE) {
                break;
            }
        }
        return results;
    }

    /** Zoho returns lists either as a bare array or under a key, depending on the endpoint. */
    private static JsonNode items(JsonNode response, String key) {
        return response != null && response.isArray() ? response : response == null ? objectless() : response.path(key);
    }

    private static JsonNode objectless() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static User toUser(JsonNode node) {
        String id = node.path("id").asText();
        String name = firstText(node, "full_name", "display_name", "name");
        return new User(id, node.path("zpuid").asText(id), name, node.path("email").asText(null));
    }

    private static Issue toIssue(JsonNode node) {
        return new Issue(node.path("id").asText(), node.path("name").asText(), node.path("description").asText(null));
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.path(field).isTextual() && !node.path(field).asText().isBlank()) {
                return node.path(field).asText();
            }
        }
        return node.path("email").asText("");
    }

    private static void putIfSet(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static void putIdIfSet(Map<String, Object> body, String key, String idField, String value) {
        if (value != null) {
            body.put(key, Map.of(idField, value));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value isn't serializable", e);
        }
    }
}
