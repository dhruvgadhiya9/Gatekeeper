package com.gatekeeper.qaplatform.auth;

/** The identity provider accepted the user, but Gatekeeper does not allow them in. */
public class LoginRejectedException extends RuntimeException {

    public LoginRejectedException(String reason) {
        super(reason);
    }
}
