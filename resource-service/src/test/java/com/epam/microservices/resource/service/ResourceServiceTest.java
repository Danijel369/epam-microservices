package com.epam.microservices.resource.service;

import com.epam.microservices.resource.client.SongServiceClient;
import com.epam.microservices.resource.entity.ResourceEntity;
import com.epam.microservices.resource.exception.InvalidFileFormatException;
import com.epam.microservices.resource.exception.InvalidMp3Exception;
import com.epam.microservices.resource.exception.StorageException;
import com.epam.microservices.resource.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    private static final byte[] DATA = {1, 2, 3, 4};
    private static final String LOCATION = "s3://resource-files/abc.mp3";

    @Mock
    private ResourceRepository repository;
    @Mock
    private StorageService storage;
    @Mock
    private Mp3Validator mp3Validator;
    @Mock
    private SongServiceClient songServiceClient;
    @Mock
    private IdParser idParser;

    @InjectMocks
    private ResourceService service;

    @Test
    void uploadStoresToS3PersistsLocationAndNeverCallsSongService() {
        when(storage.upload(DATA, "audio/mpeg")).thenReturn(LOCATION);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenAnswer(invocation -> {
            ResourceEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return entity;
        });

        long id = service.upload(DATA, "audio/mpeg");

        assertThat(id).isEqualTo(7L);

        verify(mp3Validator).validate(DATA);
        ArgumentCaptor<ResourceEntity> saved = ArgumentCaptor.forClass(ResourceEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getS3Location()).isEqualTo(LOCATION);

        verifyNoInteractions(songServiceClient);
    }

    @Test
    void uploadRejectsWrongContentTypeBeforeTouchingStorage() {
        assertThatThrownBy(() -> service.upload(DATA, "text/plain"))
                .isInstanceOf(InvalidFileFormatException.class);

        verifyNoInteractions(mp3Validator, storage, repository, songServiceClient);
    }

    @Test
    void uploadRejectsInvalidMp3BeforeTouchingStorage() {
        doThrow(new InvalidMp3Exception("Invalid MP3 file: the uploaded content is not a valid MP3"))
                .when(mp3Validator).validate(DATA);

        assertThatThrownBy(() -> service.upload(DATA, "audio/mpeg"))
                .isInstanceOf(InvalidMp3Exception.class);

        verifyNoInteractions(storage, repository, songServiceClient);
    }

    @Test
    void uploadRemovesStoredObjectWhenPersistFails() {
        when(storage.upload(DATA, "audio/mpeg")).thenReturn(LOCATION);
        when(repository.saveAndFlush(any(ResourceEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.upload(DATA, "audio/mpeg"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storage).delete(LOCATION);
        verifyNoInteractions(songServiceClient);
    }

    @Test
    void uploadPropagatesOriginalErrorWhenCompensationAlsoFails() {
        when(storage.upload(DATA, "audio/mpeg")).thenReturn(LOCATION);
        when(repository.saveAndFlush(any(ResourceEntity.class)))
                .thenThrow(new RuntimeException("db down"));
        doThrow(new StorageException("cleanup failed", null)).when(storage).delete(LOCATION);

        assertThatThrownBy(() -> service.upload(DATA, "audio/mpeg"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storage).delete(LOCATION);
    }
}
