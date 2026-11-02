package com.gatekeeper.qaplatform.common.error;

/** One invalid request field. {@code field} is a path such as {@code features[2].title}. */
public record FieldViolation(String field, String message) {}
