package com.epam.microservices.resource.service;

import com.epam.microservices.resource.exception.InvalidMp3Exception;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Validates that an uploaded body is a usable MP3 before it is stored. The check
 * is based on the content's magic bytes (via {@link Tika#detect(byte[])}),
 * independent of any client-supplied header, so that garbage sent with an
 * {@code audio/mpeg} header is still rejected.
 */
@Component
public class Mp3Validator {

    private static final String MP3_MEDIA_TYPE = "audio/mpeg";

    private final Tika tika = new Tika();

    public void validate(byte[] data) {
        if (data == null || data.length == 0) {
            throw new InvalidMp3Exception("Invalid MP3 file: request body is empty");
        }
        if (!MP3_MEDIA_TYPE.equals(detect(data))) {
            throw new InvalidMp3Exception("Invalid MP3 file: the uploaded content is not a valid MP3");
        }
    }

    private String detect(byte[] data) {
        try {
            return tika.detect(data);
        } catch (Exception e) {
            return null;
        }
    }
}
