package com.epam.microservices.resource.service;

/**
 * Stores binary resources in object storage. Each stored object is identified by
 * a location string of the form {@code s3://<bucket>/<key>}, which callers keep
 * (for example in the database) and pass back to read or delete the object.
 */
public interface StorageService {

    /**
     * Stores the given bytes under a newly generated key.
     *
     * @param data        the bytes to store.
     * @param contentType the content type recorded on the stored object.
     * @return the location of the stored object ({@code s3://<bucket>/<key>}).
     */
    String upload(byte[] data, String contentType);

    /**
     * Reads the bytes stored at the given location.
     *
     * @param location the object location returned by {@link #upload}.
     * @return the stored bytes.
     */
    byte[] download(String location);

    /**
     * Deletes the object at the given location. Idempotent: deleting a location
     * whose object no longer exists is not an error.
     *
     * @param location the object location returned by {@link #upload}.
     */
    void delete(String location);
}
