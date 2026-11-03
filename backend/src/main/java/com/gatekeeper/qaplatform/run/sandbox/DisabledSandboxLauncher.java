package com.gatekeeper.qaplatform.run.sandbox;

import com.gatekeeper.qaplatform.common.error.ServiceNotConfiguredException;
import java.util.UUID;

/** Used until sandboxes are turned on, so a run fails with a clear reason. */
public class DisabledSandboxLauncher implements SandboxLauncher {

    @Override
    public void launch(SandboxRequest request) {
        throw new ServiceNotConfiguredException("Sandboxes aren't turned on. Set SANDBOX_ENABLED=true on the server.");
    }

    @Override
    public void stop(UUID runId) {}

    @Override
    public void cleanUp() {}
}
