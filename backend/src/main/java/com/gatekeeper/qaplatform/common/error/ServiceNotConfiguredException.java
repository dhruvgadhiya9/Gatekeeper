package com.gatekeeper.qaplatform.common.error;

/** An optional integration hasn't been set up. Mapped to HTTP 503. */
public class ServiceNotConfiguredException extends RuntimeException {

    public ServiceNotConfiguredException(String message) {
        super(message);
    }
}
