package com.gatekeeper.qaplatform.routing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZohoIssueLinkRepository extends JpaRepository<ZohoIssueLink, UUID> {

    Optional<ZohoIssueLink> findByProjectIdAndFingerprint(UUID projectId, String fingerprint);
}
