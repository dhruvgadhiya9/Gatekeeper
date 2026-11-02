package com.gatekeeper.qaplatform;

import com.gatekeeper.qaplatform.common.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Replaces real storage in Spring tests. S3ObjectStorageTest covers the real implementation. */
@Component
@Primary
public class InMemoryObjectStorage implements ObjectStorage {

    public record StoredObject(byte[] content, String contentType) {}

    public final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType) {
        try {
            objects.put(key, new StoredObject(content.readAllBytes(), contentType));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
