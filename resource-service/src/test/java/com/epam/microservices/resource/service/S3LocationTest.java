package com.epam.microservices.resource.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3LocationTest {

    @Test
    void parsesCanonicalLocation() {
        S3Location loc = S3Location.parse("s3://resource-files/abc.mp3");
        assertThat(loc.bucket()).isEqualTo("resource-files");
        assertThat(loc.key()).isEqualTo("abc.mp3");
    }

    @Test
    void uriRoundTripsThroughParse() {
        String location = "s3://resource-files/2b4d.mp3";
        assertThat(S3Location.parse(location).uri()).isEqualTo(location);
    }

    @Test
    void uriBuildsCanonicalForm() {
        assertThat(new S3Location("bucket", "key.mp3").uri()).isEqualTo("s3://bucket/key.mp3");
    }

    @Test
    void keyMayContainSlashes() {
        S3Location loc = S3Location.parse("s3://bucket/path/to/key.mp3");
        assertThat(loc.bucket()).isEqualTo("bucket");
        assertThat(loc.key()).isEqualTo("path/to/key.mp3");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> S3Location.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingScheme() {
        assertThatThrownBy(() -> S3Location.parse("resource-files/abc.mp3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingKey() {
        assertThatThrownBy(() -> S3Location.parse("s3://resource-files"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyKey() {
        assertThatThrownBy(() -> S3Location.parse("s3://resource-files/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyBucket() {
        assertThatThrownBy(() -> S3Location.parse("s3:///abc.mp3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsBlankBucketOrKey() {
        assertThatThrownBy(() -> new S3Location(" ", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3Location("b", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
