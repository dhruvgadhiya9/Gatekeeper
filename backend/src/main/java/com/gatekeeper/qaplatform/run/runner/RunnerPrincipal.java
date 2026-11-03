package com.gatekeeper.qaplatform.run.runner;

import java.util.UUID;

/** The sandbox making a request, identified by its job token. */
public record RunnerPrincipal(UUID runId) {}
