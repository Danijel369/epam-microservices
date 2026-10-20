package com.epam.microservices.resource.service;

/**
 * The bucket and key of a stored object, together with its canonical
 * {@code s3://<bucket>/<key>} string form. Parsing rejects anything that is not
 * a well-formed location with a non-empty bucket and key.
 */
public record S3Location(String bucket, String key) {

    private static final String SCHEME = "s3://";

    public S3Location {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("S3 location requires a non-empty bucket and key");
        }
    }

    /**
     * Parses a canonical location string of the form {@code s3://<bucket>/<key>}.
     *
     * @throws IllegalArgumentException if the string is not a well-formed location.
     */
    public static S3Location parse(String location) {
        if (location == null || !location.startsWith(SCHEME)) {
            throw new IllegalArgumentException("Not an S3 location: " + location);
        }
        String path = location.substring(SCHEME.length());
        int slash = path.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("S3 location is missing a key: " + location);
        }
        return new S3Location(path.substring(0, slash), path.substring(slash + 1));
    }

    /**
     * @return the canonical {@code s3://<bucket>/<key>} form.
     */
    public String uri() {
        return SCHEME + bucket + "/" + key;
    }
}
