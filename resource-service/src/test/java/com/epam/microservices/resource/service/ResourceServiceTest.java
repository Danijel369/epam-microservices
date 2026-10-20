package com.epam.microservices.resource.service;

import com.epam.microservices.resource.client.SongServiceClient;
import com.epam.microservices.resource.entity.ResourceEntity;
import com.epam.microservices.resource.exception.DownstreamException;
import com.epam.microservices.resource.exception.InvalidCsvTokenException;
import com.epam.microservices.resource.exception.InvalidFileFormatException;
import com.epam.microservices.resource.exception.InvalidIdException;
import com.epam.microservices.resource.exception.InvalidMp3Exception;
import com.epam.microservices.resource.exception.ResourceNotFoundException;
import com.epam.microservices.resource.exception.StorageException;
import com.epam.microservices.resource.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Test
    void getDataReturnsBytesDownloadedFromS3() {
        byte[] bytes = {5, 6, 7};
        ResourceEntity entity = new ResourceEntity();
        entity.setS3Location(LOCATION);
        when(idParser.parsePathId("1")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(storage.download(LOCATION)).thenReturn(bytes);

        assertThat(service.getData("1")).isEqualTo(bytes);
    }

    @Test
    void getDataThrowsNotFoundForUnknownId() {
        when(idParser.parsePathId("2")).thenReturn(2L);
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getData("2"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(storage);
    }

    @Test
    void getDataRejectsMalformedIdBeforeTouchingStorage() {
        when(idParser.parsePathId("abc")).thenThrow(new InvalidIdException("abc"));

        assertThatThrownBy(() -> service.getData("abc"))
                .isInstanceOf(InvalidIdException.class);

        verifyNoInteractions(repository, storage);
    }

    @Test
    void getDataSurfacesStorageErrorWhenObjectMissing() {
        ResourceEntity entity = new ResourceEntity();
        entity.setS3Location(LOCATION);
        when(idParser.parsePathId("1")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(storage.download(LOCATION)).thenThrow(new StorageException("missing object", null));

        assertThatThrownBy(() -> service.getData("1"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void deleteRemovesS3ObjectAndDbRowForExistingIdsThenCascades() {
        String location2 = "s3://resource-files/def.mp3";
        ResourceEntity entity1 = new ResourceEntity();
        entity1.setS3Location(LOCATION);
        ResourceEntity entity2 = new ResourceEntity();
        entity2.setS3Location(location2);
        when(idParser.parseCsv("1,2")).thenReturn(List.of(1L, 2L));
        when(repository.findById(1L)).thenReturn(Optional.of(entity1));
        when(repository.findById(2L)).thenReturn(Optional.of(entity2));

        assertThat(service.delete("1,2")).containsExactly(1L, 2L);

        verify(storage).delete(LOCATION);
        verify(storage).delete(location2);
        verify(repository).delete(entity1);
        verify(repository).delete(entity2);
        verify(songServiceClient).deleteMetadata(List.of(1L, 2L));
    }

    @Test
    void deleteIgnoresIdsThatDoNotExist() {
        ResourceEntity entity1 = new ResourceEntity();
        entity1.setS3Location(LOCATION);
        when(idParser.parseCsv("1,2")).thenReturn(List.of(1L, 2L));
        when(repository.findById(1L)).thenReturn(Optional.of(entity1));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.delete("1,2")).containsExactly(1L);

        verify(storage).delete(LOCATION);
        verify(repository).delete(entity1);
        verify(songServiceClient).deleteMetadata(List.of(1L));
    }

    @Test
    void deleteWithNoMatchingIdsSkipsStorageAndCascadesEmptyList() {
        when(idParser.parseCsv("9,10")).thenReturn(List.of(9L, 10L));
        when(repository.findById(9L)).thenReturn(Optional.empty());
        when(repository.findById(10L)).thenReturn(Optional.empty());

        assertThat(service.delete("9,10")).isEmpty();

        verifyNoInteractions(storage);
        verify(repository, never()).delete(any(ResourceEntity.class));
        verify(songServiceClient).deleteMetadata(List.of());
    }

    @Test
    void deletePropagatesSongServiceFailureAfterLocalDeletes() {
        ResourceEntity entity1 = new ResourceEntity();
        entity1.setS3Location(LOCATION);
        when(idParser.parseCsv("1")).thenReturn(List.of(1L));
        when(repository.findById(1L)).thenReturn(Optional.of(entity1));
        doThrow(new DownstreamException(500, "song service down"))
                .when(songServiceClient).deleteMetadata(List.of(1L));

        assertThatThrownBy(() -> service.delete("1"))
                .isInstanceOf(DownstreamException.class);

        verify(storage).delete(LOCATION);
        verify(repository).delete(entity1);
    }

    @Test
    void deleteRejectsInvalidCsvTokenBeforeAnySideEffects() {
        when(idParser.parseCsv("x")).thenThrow(new InvalidCsvTokenException("x"));

        assertThatThrownBy(() -> service.delete("x"))
                .isInstanceOf(InvalidCsvTokenException.class);

        verifyNoInteractions(repository, storage, songServiceClient);
    }
}
