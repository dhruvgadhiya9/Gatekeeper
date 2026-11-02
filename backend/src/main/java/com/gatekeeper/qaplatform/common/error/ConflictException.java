package com.gatekeeper.qaplatform.common.error;

/** The request is valid but not allowed in the current state. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
