package com.gatekeeper.qaplatform.common.storage;

import com.gatekeeper.qaplatform.config.StorageProperties;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;
    private volatile boolean bucketReady;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
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

    @Override
    public String presignGet(String key, Duration validFor) {
        return presigner.presignGetObject(request -> request
                        .signatureDuration(validFor)
                        .getObjectRequest(get -> get.bucket(properties.bucket()).key(key)))
                .url()
                .toString();
    }

    @Override
    public String presignPut(String key, long sizeBytes, String contentType, Duration validFor) {
        ensureBucket();
        return presigner.presignPutObject(request -> request
                        .signatureDuration(validFor)
                        .putObjectRequest(put -> put.bucket(properties.bucket())
                                .key(key)
                                .contentType(contentType)
                                .contentLength(sizeBytes)))
                .url()
                .toString();
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
