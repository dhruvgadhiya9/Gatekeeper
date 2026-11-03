package com.gatekeeper.qaplatform.run.llm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, UUID> {

    List<LlmUsage> findByRunId(UUID runId);
}
