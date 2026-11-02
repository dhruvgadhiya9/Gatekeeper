package com.gatekeeper.qaplatform.run;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestPlanRepository extends JpaRepository<TestPlan, UUID> {

    Optional<TestPlan> findByRunId(UUID runId);
}
