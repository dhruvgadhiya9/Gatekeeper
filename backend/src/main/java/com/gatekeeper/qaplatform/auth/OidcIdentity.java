package com.gatekeeper.qaplatform.auth;

/** The claims from an identity provider's ID token that Gatekeeper uses. */
public record OidcIdentity(
        String issuer, String subject, String email, boolean emailVerified, String displayName) {}
