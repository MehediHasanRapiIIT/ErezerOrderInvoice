package com.erezer.admin.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3-compatible object storage (MinIO).
 *
 * @param endpoint         URL the backend uses to reach storage (e.g. {@code http://minio:9000} inside Docker)
 * @param publicEndpoint   URL browsers use for presigned links; differs from {@code endpoint} behind Docker/NAT
 * @param initializeBucket create the bucket on startup when missing
 */
@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @NotNull URI endpoint,
        @NotNull URI publicEndpoint,
        @NotBlank String region,
        @NotBlank(message = "STORAGE_ACCESS_KEY must be set") String accessKey,
        @NotBlank(message = "STORAGE_SECRET_KEY must be set") String secretKey,
        @NotBlank String bucket,
        boolean initializeBucket,
        @NotNull Duration presignedUrlTtl) {
}
