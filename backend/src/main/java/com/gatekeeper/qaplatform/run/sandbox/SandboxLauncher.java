package com.gatekeeper.qaplatform.run.sandbox;

import java.util.UUID;

/** Starts and removes sandboxes. A Kubernetes implementation can be added beside Docker's (Phase 10). */
public interface SandboxLauncher {

    void launch(SandboxRequest request);

    /** Removes everything belonging to a run. Does nothing if it's already gone. */
    void stop(UUID runId);

    /** Removes sandboxes past their deadline, and what finished sandboxes left behind. */
    void cleanUp();
}
