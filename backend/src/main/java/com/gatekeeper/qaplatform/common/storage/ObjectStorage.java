package com.gatekeeper.qaplatform.common.storage;

import java.io.InputStream;

/** Stores artifacts such as design images, screenshots and reports. */
public interface ObjectStorage {

    void put(String key, InputStream content, long sizeBytes, String contentType);

    /** Does nothing if the object doesn't exist. */
    void delete(String key);
}
