package com.gatekeeper.qaplatform.run;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    List<TestRun> findByProjectId(UUID projectId);

    boolean existsByProjectId(UUID projectId);

    /** Done in the database, because several agents can report usage for one run at once. */
    @Transactional
    @Modifying
    @Query("update TestRun r set r.tokenUsage = r.tokenUsage + :tokens, r.costUsd = r.costUsd + :cost where r.id = :id")
    void addUsage(UUID id, long tokens, BigDecimal cost);
}
