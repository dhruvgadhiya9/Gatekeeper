package com.gatekeeper.qaplatform;

import com.gatekeeper.qaplatform.common.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Replaces real storage in Spring tests. S3ObjectStorageTest covers the real implementation. */
@Component
@Primary
@ConditionalOnProperty(name = "test.storage.in-memory", havingValue = "true", matchIfMissing = true)
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

    @Override
    public String presignGet(String key, Duration validFor) {
        return "https://storage.test/get/" + key;
    }

    @Override
    public String presignPut(String key, long sizeBytes, String contentType, Duration validFor) {
        return "https://storage.test/put/" + key;
    }
}
