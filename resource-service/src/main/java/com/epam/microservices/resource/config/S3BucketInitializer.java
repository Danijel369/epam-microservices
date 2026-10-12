package com.epam.microservices.resource.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Ensures the storage bucket exists when the application starts. The check is
 * idempotent: an existing bucket is left untouched, a missing one is created.
 */
@Component
public class S3BucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(S3BucketInitializer.class);

    private final S3Client s3;
    private final S3Properties props;

    public S3BucketInitializer(S3Client s3, S3Properties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String bucket = props.bucket();
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("S3 bucket '{}' already exists", bucket);
        } catch (S3Exception e) {
            // A HEAD request carries no response body, so a missing bucket may
            // surface as a generic 404 S3Exception rather than NoSuchBucketException.
            if (e.statusCode() == 404 || e instanceof NoSuchBucketException) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Created S3 bucket '{}'", bucket);
            } else {
                throw e;
            }
        }
    }
}
