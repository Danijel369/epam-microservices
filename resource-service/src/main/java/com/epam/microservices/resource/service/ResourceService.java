package com.epam.microservices.resource.service;

import com.epam.microservices.resource.client.SongServiceClient;
import com.epam.microservices.resource.entity.ResourceEntity;
import com.epam.microservices.resource.exception.InvalidFileFormatException;
import com.epam.microservices.resource.exception.ResourceNotFoundException;
import com.epam.microservices.resource.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResourceService {

    private static final String MP3_CONTENT_TYPE = "audio/mpeg";

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);

    private final ResourceRepository repository;
    private final StorageService storage;
    private final Mp3Validator mp3Validator;
    private final SongServiceClient songServiceClient;
    private final IdParser idParser;

    public ResourceService(ResourceRepository repository,
                           StorageService storage,
                           Mp3Validator mp3Validator,
                           SongServiceClient songServiceClient,
                           IdParser idParser) {
        this.repository = repository;
        this.storage = storage;
        this.mp3Validator = mp3Validator;
        this.songServiceClient = songServiceClient;
        this.idParser = idParser;
    }

    /**
     * Validates the upload, stores the MP3 in object storage and records its
     * location. If persisting the location fails, the stored object is removed
     * so that no orphan is left behind.
     */
    public long upload(byte[] data, String contentType) {
        validateContentType(contentType);
        mp3Validator.validate(data);

        String location = storage.upload(data, contentType);
        try {
            ResourceEntity entity = new ResourceEntity();
            entity.setS3Location(location);
            return repository.saveAndFlush(entity).getId();
        } catch (RuntimeException e) {
            deleteQuietly(location);
            throw e;
        }
    }

    /**
     * Best-effort removal of a stored object after a failed persist. A failure
     * here is logged but never masks the original error.
     */
    private void deleteQuietly(String location) {
        try {
            storage.delete(location);
        } catch (RuntimeException cleanupError) {
            log.warn("Failed to remove orphaned object {} after a persistence failure", location, cleanupError);
        }
    }

    @Transactional(readOnly = true)
    public byte[] getData(String rawId) {
        long id = idParser.parsePathId(rawId);
        return repository.findById(id)
                .map(ResourceEntity::getS3Location)
                .map(storage::download)
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /**
     * Deletes the given resources (ignoring ids that do not exist) and cascades
     * deletion of the matching song metadata. A Song Service failure rolls back
     * the resource deletions.
     */
    @Transactional
    public List<Long> delete(String csv) {
        List<Long> requestedIds = idParser.parseCsv(csv);

        List<Long> deletedIds = new ArrayList<>();
        for (Long id : requestedIds) {
            if (repository.existsById(id)) {
                repository.deleteById(id);
                deletedIds.add(id);
            }
        }

        songServiceClient.deleteMetadata(deletedIds);
        return deletedIds;
    }

    private void validateContentType(String contentType) {
        String normalized = normalize(contentType);
        if (!MP3_CONTENT_TYPE.equalsIgnoreCase(normalized)) {
            throw new InvalidFileFormatException(normalized);
        }
    }

    private String normalize(String contentType) {
        if (contentType == null) {
            return "null";
        }
        int semicolon = contentType.indexOf(';');
        String value = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return value.trim();
    }
}
