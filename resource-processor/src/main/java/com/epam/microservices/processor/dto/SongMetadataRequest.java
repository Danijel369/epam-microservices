package com.epam.microservices.processor.dto;

/**
 * Payload for storing metadata extracted from an MP3 file in the Song Service.
 * Mirrors the Song Service request shape (id, name, artist, album, duration,
 * year) and is kept here for later integration. Values are passed through from
 * the MP3 tags, except {@code duration}, which is converted to mm:ss.
 */
public record SongMetadataRequest(
        Long id,
        String name,
        String artist,
        String album,
        String duration,
        String year) {
}
