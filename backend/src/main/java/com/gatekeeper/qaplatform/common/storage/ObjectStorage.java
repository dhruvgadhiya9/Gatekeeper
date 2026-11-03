package com.gatekeeper.qaplatform.common.storage;

import java.io.InputStream;
import java.time.Duration;

/** Stores artifacts such as design images, screenshots and reports. */
public interface ObjectStorage {

    void put(String key, InputStream content, long sizeBytes, String contentType);

    /** Does nothing if the object doesn't exist. */
    void delete(String key);

    /** A URL that can read this one object until it expires. */
    String presignGet(String key, Duration validFor);

    /**
     * A URL that can write this one object until it expires. The size and type are part of the
     * signature, so the uploader must send exactly those and can't store anything larger.
     */
    String presignPut(String key, long sizeBytes, String contentType, Duration validFor);
}
