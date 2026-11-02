package com.gatekeeper.qaplatform.auth;

import java.util.UUID;

/** The signed-in user, available to controllers through {@code @AuthenticationPrincipal}. */
public record AuthenticatedUser(UUID id, String email, Role role) {}
