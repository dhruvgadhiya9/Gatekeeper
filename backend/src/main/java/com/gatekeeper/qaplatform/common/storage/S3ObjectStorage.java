package com.gatekeeper.qaplatform.common.storage;

import com.gatekeeper.qaplatform.config.StorageProperties;
import java.io.InputStream;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final StorageProperties properties;
    private volatile boolean bucketReady;

    public S3ObjectStorage(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
        this.bucketReady = !properties.createBucket();
    }

    @Override
    public void put(String key, InputStream content, long sizeBytes, String contentType) {
        ensureBucket();
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength(sizeBytes)
                        .build(),
                RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            s3.createBucket(request -> request.bucket(properties.bucket()));
        } catch (BucketAlreadyOwnedByYouException alreadyExists) {
            // Another request or an earlier run created it.
        }
        bucketReady = true;
    }
}
