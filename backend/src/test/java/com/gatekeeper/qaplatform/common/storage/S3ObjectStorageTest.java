package com.gatekeeper.qaplatform.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.config.StorageConfig;
import com.gatekeeper.qaplatform.config.StorageProperties;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Testcontainers
class S3ObjectStorageTest {

    private static final String ACCESS_KEY = "testaccess";
    private static final String SECRET_KEY = "testsecretvalue";
    /** The same image as infra/docker-compose.yml. */
    private static final String MINIO_IMAGE =
            "chainguard/minio@sha256:0a01100085e30fb08d2f39e39c9f5c9d83a95707f220b007d813b690cd41622d";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
            .withCommand("server", "/tmp/data")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Test
    void createsBucketAndStoresObjectWithItsContentType() {
        StorageProperties properties = new StorageProperties(
                "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000),
                "us-east-1",
                "test-bucket",
                ACCESS_KEY,
                SECRET_KEY,
                true);
        byte[] content = {1, 2, 3, 4};

        try (S3Client client = new StorageConfig().s3Client(properties)) {
            S3ObjectStorage storage = new S3ObjectStorage(client, properties);
            storage.put("a/first.png", new ByteArrayInputStream(content), content.length, "image/png");
            storage.put("a/second.png", new ByteArrayInputStream(content), content.length, "image/png");

            ResponseBytes<GetObjectResponse> stored =
                    client.getObjectAsBytes(request -> request.bucket("test-bucket").key("a/first.png"));
            assertThat(stored.asByteArray()).isEqualTo(content);
            assertThat(stored.response().contentType()).isEqualTo("image/png");

            storage.delete("a/first.png");
            storage.delete("a/never-existed.png");
            assertThatThrownBy(() -> client.getObjectAsBytes(request -> request.bucket("test-bucket").key("a/first.png")))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }
}
