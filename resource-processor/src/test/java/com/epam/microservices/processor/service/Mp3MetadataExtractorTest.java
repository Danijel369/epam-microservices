package com.epam.microservices.processor.service;

import com.epam.microservices.processor.dto.Mp3Tags;
import com.epam.microservices.processor.exception.InvalidMp3Exception;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp3MetadataExtractorTest {

    private final Mp3MetadataExtractor extractor = new Mp3MetadataExtractor();

    private byte[] readFixture(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("test fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void extractsAllTagsFromAFullyTaggedMp3() throws IOException {
        Mp3Tags tags = extractor.extract(readFixture("mp3-with-all-tags.mp3"));

        assertThat(tags.name()).isEqualTo("Test Title");
        assertThat(tags.artist()).isEqualTo("Test Artist");
        assertThat(tags.album()).isEqualTo("Test Album");
        assertThat(tags.year()).isEqualTo("2025");
        assertThat(tags.duration()).isEqualTo("00:07");
        assertThat(tags.duration()).matches("^[0-5][0-9]:[0-5][0-9]$");
    }

    @Test
    void handlesMissingTagsGracefully() throws IOException {
        Mp3Tags tags = extractor.extract(readFixture("mp3-with-missing-tags.mp3"));

        assertThat(tags.name()).isEqualTo("Valid Title");
        assertThat(tags.artist()).isNullOrEmpty();
        assertThat(tags.album()).isNullOrEmpty();
        assertThat(tags.year()).isEqualTo("2025");
        assertThat(tags.duration()).matches("^[0-5][0-9]:[0-5][0-9]$");
    }

    @Test
    void throwsForEmptyBytes() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(InvalidMp3Exception.class);
    }
}
