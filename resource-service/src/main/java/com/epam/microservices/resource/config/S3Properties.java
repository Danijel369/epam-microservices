package com.epam.microservices.resource.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the S3 object storage that holds the MP3 files. Bound from the
 * {@code s3.*} properties, which default to a local LocalStack endpoint and are
 * overridden by environment variables in Docker.
 *
 * @param endpoint  S3 endpoint URL (the LocalStack edge port).
 * @param region    AWS region the client is configured for.
 * @param bucket    bucket that stores the uploaded files.
 * @param accessKey access key (dummy for the emulator).
 * @param secretKey secret key (dummy for the emulator).
 * @param pathStyle whether to use path-style access, required for a custom endpoint.
 */
@ConfigurationProperties(prefix = "s3")
public record S3Properties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle) {
}
