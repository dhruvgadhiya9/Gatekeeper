package com.gatekeeper.qaplatform.integration.github;

import com.gatekeeper.qaplatform.integration.github.GitHubModels.Installation;
import com.gatekeeper.qaplatform.integration.github.GitHubModels.Repository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feeds the repository picker in the project wizard. */
@RestController
@RequestMapping("/api/integrations/github")
public class GitHubController {

    public record Status(boolean configured) {}

    private final GitHubClient github;

    public GitHubController(GitHubClient github) {
        this.github = github;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(github.isConfigured());
    }

    @GetMapping("/installations")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<Installation> installations() {
        return github.listInstallations();
    }

    @GetMapping("/installations/{id}/repositories")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
    public List<Repository> repositories(@PathVariable long id) {
        return github.listRepositories(id);
    }
}
