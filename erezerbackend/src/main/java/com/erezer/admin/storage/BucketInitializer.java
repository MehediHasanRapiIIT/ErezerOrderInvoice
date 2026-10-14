package com.erezer.admin.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Ensures the bucket exists. Fails startup when storage is unreachable, so a misconfiguration
 * is caught at deploy time instead of on the first image upload.
 */
@Component
@ConditionalOnBooleanProperty(name = "app.storage.initialize-bucket")
class BucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketInitializer.class);

    private final S3Client s3;
    private final StorageProperties properties;

    BucketInitializer(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String bucket = properties.bucket();
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException ex) {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Created storage bucket '{}'", bucket);
        }
    }
}
