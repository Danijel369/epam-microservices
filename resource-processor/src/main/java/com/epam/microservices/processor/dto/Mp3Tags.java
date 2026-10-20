package com.epam.microservices.processor.dto;

/**
 * Tags extracted from an MP3 file. {@code duration} is already converted to mm:ss.
 * All other fields are passed through unchanged from the file's metadata.
 */
public record Mp3Tags(
        String name,
        String artist,
        String album,
        String duration,
        String year) {
}
