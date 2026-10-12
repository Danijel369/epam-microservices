package com.epam.microservices.resource.service;

import com.epam.microservices.resource.exception.InvalidMp3Exception;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp3ValidatorTest {

    private final Mp3Validator validator = new Mp3Validator();

    @Test
    void acceptsContentWithMp3MagicBytes() {
        // An ID3v2 tag header is the canonical MP3 signature Tika recognises.
        byte[] mp3 = {'I', 'D', '3', 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        assertThatCode(() -> validator.validate(mp3)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNullBody() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidMp3Exception.class);
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> validator.validate(new byte[0]))
                .isInstanceOf(InvalidMp3Exception.class);
    }

    @Test
    void rejectsNonMp3Content() {
        byte[] notMp3 = "this is plain text, not an mp3 file".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(notMp3))
                .isInstanceOf(InvalidMp3Exception.class);
    }
}
