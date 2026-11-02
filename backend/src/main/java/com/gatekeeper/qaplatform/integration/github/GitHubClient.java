package com.gatekeeper.qaplatform.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.config.GitHubProperties;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.BlameRange;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.CloneCredentials;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.Installation;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.Repository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Talks to GitHub as a GitHub App. Tokens only ever appear in request headers, and are never logged
 * or stored outside this process's memory.
 */
@Component
public class GitHubClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final String RAW_CONTENT = "application/vnd.github.raw+json";
    /** GitHub checks these places in this order. */
    private static final List<String> CODEOWNERS_PATHS = List.of(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    /** Renews tokens this long before they expire, so one can't run out in the middle of a call. */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);

    private static final String BLAME_QUERY = """
            query($owner: String!, $name: String!, $ref: String!, $path: String!) {
              repository(owner: $owner, name: $name) {
                object(expression: $ref) {
                  ... on Commit {
                    blame(path: $path) {
                      ranges {
                        startingLine
                        endingLine
                        commit { oid committedDate author { email user { login } } }
                      }
                    }
                  }
                }
              }
            }
            """;

    private record CachedToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + ", token=***]";
        }
    }

    private final RestClient rest;
    private final GitHubAppJwtSigner jwtSigner;
    private final GitHubProperties properties;
    private final Clock clock;
    private final Map<Long, CachedToken> installationTokens = new ConcurrentHashMap<>();

    public GitHubClient(
            RestClient.Builder builder, GitHubAppJwtSigner jwtSigner, GitHubProperties properties, Clock clock) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = builder
                .baseUrl(properties.apiUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-GitHub-Api-Version", properties.apiVersion())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
        this.jwtSigner = jwtSigner;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Audited(action = "GITHUB_LIST_INSTALLATIONS")
    @Retry(name = "github")
    @CircuitBreaker(name = "github")
    public List<Installation> listInstallations() {
        String jwt = jwtSigner.createJwt();
        return fetchPages(
                page -> rest.get()
                        .uri("/app/installations?per_page={size}&page={page}", PAGE_SIZE, page)
                        .headers(headers -> headers.setBearerAuth(jwt))
                        .retrieve()
                        .body(JsonNode.class),
                body -> body,
                node -> new Installation(
                        node.path("id").asLong(),
                        node.path("account").path("login").asText(),
                        node.path("account").path("type").asText()));
    }

    @Audited(action = "GITHUB_LIST_REPOSITORIES", target = "#installationId")
    @Retry(name = "github")
    @CircuitBreaker(name = "github")
    public List<Repository> listRepositories(long installationId) {
        String token = installationToken(installationId);
        return fetchPages(
                page -> rest.get()
                        .uri("/installation/repositories?per_page={size}&page={page}", PAGE_SIZE, page)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(JsonNode.class),
                body -> body.path("repositories"),
                node -> new Repository(
                        node.path("full_name").asText(),
                        node.path("default_branch").asText(),
                        node.path("private").asBoolean()));
    }

    /**
     * Creates a token that can only read this one repository's contents, and expires within an hour.
     * This is the only GitHub credential that may leave the backend.
     */
    @Audited(action = "GITHUB_CLONE_TOKEN", target = "#repoFullName")
    @Retry(name = "github")
    @CircuitBreaker(name = "github")
    public CloneCredentials createCloneCredentials(long installationId, String repoFullName) {
        String[] parts = split(repoFullName);
        JsonNode response = rest.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .headers(headers -> headers.setBearerAuth(jwtSigner.createJwt()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("repositories", List.of(parts[1]), "permissions", Map.of("contents", "read")))
                .retrieve()
                .body(JsonNode.class);
        return new CloneCredentials(
                response.path("token").asText(),
                Instant.parse(response.path("expires_at").asText()),
                properties.webUrl() + "/" + repoFullName + ".git");
    }

    /** Returns the repository's CODEOWNERS file, or empty if it has none. */
    @Audited(action = "GITHUB_READ_CODEOWNERS", target = "#repoFullName")
    @Retry(name = "github")
    @CircuitBreaker(name = "github")
    public Optional<String> readCodeowners(long installationId, String repoFullName) {
        String[] parts = split(repoFullName);
        String token = installationToken(installationId);
        for (String path : CODEOWNERS_PATHS) {
            try {
                return Optional.ofNullable(rest.get()
                        .uri("/repos/{owner}/{repo}/contents/" + path, parts[0], parts[1])
                        .headers(headers -> {
                            headers.setBearerAuth(token);
                            headers.set(HttpHeaders.ACCEPT, RAW_CONTENT);
                        })
                        .retrieve()
                        .body(String.class));
            } catch (HttpClientErrorException.NotFound missing) {
                // Try the next place.
            }
        }
        return Optional.empty();
    }

    /**
     * Returns who last changed each range overlapping the given lines. Blame exists only in GitHub's
     * GraphQL API. Values go in as GraphQL variables, so a file path can't change the query. A file
     * or ref that doesn't exist gives an empty list.
     */
    @Audited(action = "GITHUB_BLAME", target = "#repoFullName")
    @Retry(name = "github")
    @CircuitBreaker(name = "github")
    public List<BlameRange> blame(
            long installationId, String repoFullName, String ref, String path, int startLine, int endLine) {
        String[] parts = split(repoFullName);
        JsonNode response = rest.post()
                .uri("/graphql")
                .headers(headers -> headers.setBearerAuth(installationToken(installationId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query", BLAME_QUERY,
                        "variables", Map.of("owner", parts[0], "name", parts[1], "ref", ref, "path", path)))
                .retrieve()
                .body(JsonNode.class);

        List<BlameRange> ranges = new ArrayList<>();
        for (JsonNode node : response.path("data").path("repository").path("object").path("blame").path("ranges")) {
            int start = node.path("startingLine").asInt();
            int end = node.path("endingLine").asInt();
            if (start <= endLine && end >= startLine) {
                JsonNode commit = node.path("commit");
                JsonNode login = commit.path("author").path("user").path("login");
                ranges.add(new BlameRange(
                        start,
                        end,
                        commit.path("oid").asText(),
                        Instant.parse(commit.path("committedDate").asText()),
                        login.isTextual() ? login.asText() : null,
                        commit.path("author").path("email").asText(null)));
            }
        }
        return ranges;
    }

    /** Installation tokens last an hour, so they're reused to avoid an extra call each time. */
    private String installationToken(long installationId) {
        CachedToken cached = installationTokens.get(installationId);
        if (cached != null && clock.instant().isBefore(cached.expiresAt().minus(TOKEN_MARGIN))) {
            return cached.token();
        }
        JsonNode response = rest.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .headers(headers -> headers.setBearerAuth(jwtSigner.createJwt()))
                .retrieve()
                .body(JsonNode.class);
        CachedToken fresh =
                new CachedToken(response.path("token").asText(), Instant.parse(response.path("expires_at").asText()));
        installationTokens.put(installationId, fresh);
        return fresh.token();
    }

    private static <T> List<T> fetchPages(
            Function<Integer, JsonNode> fetch, Function<JsonNode, JsonNode> items, Function<JsonNode, T> mapper) {
        List<T> results = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int count = 0;
            for (JsonNode node : items.apply(fetch.apply(page))) {
                results.add(mapper.apply(node));
                count++;
            }
            if (count < PAGE_SIZE) {
                break;
            }
        }
        return results;
    }

    private static String[] split(String repoFullName) {
        if (repoFullName == null || !REPOSITORY.matcher(repoFullName).matches()) {
            throw new RequestValidationException("repoFullName", "must look like owner/repository");
        }
        return repoFullName.split("/");
    }
}
