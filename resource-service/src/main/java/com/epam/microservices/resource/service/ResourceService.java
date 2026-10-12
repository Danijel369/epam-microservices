package com.epam.microservices.resource.service;

import com.epam.microservices.resource.client.SongServiceClient;
import com.epam.microservices.resource.dto.Mp3Tags;
import com.epam.microservices.resource.dto.SongMetadataRequest;
import com.epam.microservices.resource.entity.ResourceEntity;
import com.epam.microservices.resource.exception.InvalidFileFormatException;
import com.epam.microservices.resource.exception.ResourceNotFoundException;
import com.epam.microservices.resource.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResourceService {

    private static final String MP3_CONTENT_TYPE = "audio/mpeg";

    private final ResourceRepository repository;
    private final StorageService storage;
    private final Mp3MetadataExtractor extractor;
    private final SongServiceClient songServiceClient;
    private final IdParser idParser;

    public ResourceService(ResourceRepository repository,
                           StorageService storage,
                           Mp3MetadataExtractor extractor,
                           SongServiceClient songServiceClient,
                           IdParser idParser) {
        this.repository = repository;
        this.storage = storage;
        this.extractor = extractor;
        this.songServiceClient = songServiceClient;
        this.idParser = idParser;
    }

    /**
     * Stores the MP3, extracts its tags and hands them to the Song Service.
     * A failure of the Song Service rolls back the stored resource.
     */
    @Transactional
    public long upload(byte[] data, String contentType) {
        validateContentType(contentType);

        Mp3Tags tags = extractor.extract(data);

        String location = storage.upload(data, contentType);

        ResourceEntity entity = new ResourceEntity();
        entity.setS3Location(location);
        ResourceEntity saved = repository.saveAndFlush(entity);

        SongMetadataRequest metadata = new SongMetadataRequest(
                saved.getId(), tags.name(), tags.artist(), tags.album(), tags.duration(), tags.year());
        songServiceClient.createMetadata(metadata);

        return saved.getId();
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
