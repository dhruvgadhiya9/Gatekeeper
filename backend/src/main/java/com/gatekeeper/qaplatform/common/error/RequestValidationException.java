package com.gatekeeper.qaplatform.common.error;

import java.util.List;

/** Validation that annotations can't express. Mapped to HTTP 400, like annotation failures. */
public class RequestValidationException extends RuntimeException {

    private final transient List<FieldViolation> violations;

    public RequestValidationException(List<FieldViolation> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public RequestValidationException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
