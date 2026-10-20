package com.epam.microservices.resource.exception;

/**
 * Thrown when an object storage operation fails. Wraps the underlying storage
 * error so that callers do not depend on the storage SDK's exception types.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
