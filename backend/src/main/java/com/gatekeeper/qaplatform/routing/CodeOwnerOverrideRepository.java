package com.gatekeeper.qaplatform.routing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeOwnerOverrideRepository extends JpaRepository<CodeOwnerOverride, UUID> {

    List<CodeOwnerOverride> findByProjectId(UUID projectId);
}
