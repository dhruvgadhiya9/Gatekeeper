package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.finding.Finding;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentMappingRepository extends JpaRepository<DepartmentMapping, UUID> {

    Optional<DepartmentMapping> findByProjectIdAndCategory(UUID projectId, Finding.Category category);
}
