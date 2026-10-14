package com.erezer.admin.storage;

import java.io.InputStream;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    S3StorageService(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(content, contentLength));
    }

    @Override
    public InputStream get(String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(properties.bucket()).key(key));
    }

    @Override
    public URL presignedGetUrl(String key) {
        return presigner.presignGetObject(p -> p
                        .signatureDuration(properties.presignedUrlTtl())
                        .getObjectRequest(r -> r.bucket(properties.bucket()).key(key)))
                .url();
    }

    @Override
    public boolean isAvailable() {
        try {
            s3.headBucket(b -> b.bucket(properties.bucket()));
            return true;
        } catch (RuntimeException ex) {
            log.debug("Storage health check failed", ex);
            return false;
        }
    }
}
