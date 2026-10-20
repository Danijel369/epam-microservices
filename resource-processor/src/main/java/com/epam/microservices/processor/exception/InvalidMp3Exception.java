package com.epam.microservices.processor.exception;

/**
 * Thrown when the bytes handed to the extractor are not a usable MP3 (empty body
 * or content that is not actually an MP3).
 */
public class InvalidMp3Exception extends RuntimeException {

    public InvalidMp3Exception(String message) {
        super(message);
    }
}
