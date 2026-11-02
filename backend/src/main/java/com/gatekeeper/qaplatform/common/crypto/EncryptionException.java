package com.gatekeeper.qaplatform.common.crypto;

/** Encryption or decryption failed. The message never contains key or data material. */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
