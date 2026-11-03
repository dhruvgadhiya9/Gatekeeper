package com.gatekeeper.qaplatform.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMappingRepository extends JpaRepository<UserMapping, UUID> {

    Optional<UserMapping> findByGithubUsername(String githubUsername);

    List<UserMapping> findAllByOrderByGithubUsernameAsc();
}
