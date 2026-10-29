package com.epam.microservices.processor.messaging;

import com.epam.microservices.processor.client.ResourceServiceClient;
import com.epam.microservices.processor.client.SongServiceClient;
import com.epam.microservices.processor.dto.Mp3Tags;
import com.epam.microservices.processor.dto.SongMetadataRequest;
import com.epam.microservices.processor.exception.InvalidMp3Exception;
import com.epam.microservices.processor.service.Mp3MetadataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class ResourceEventListener {

    private static final Logger log = LoggerFactory.getLogger(ResourceEventListener.class);

    private final ResourceServiceClient resourceServiceClient;
    private final SongServiceClient songServiceClient;
    private final Mp3MetadataExtractor mp3MetadataExtractor;

    public ResourceEventListener(ResourceServiceClient resourceServiceClient,
                                  SongServiceClient songServiceClient,
                                  Mp3MetadataExtractor mp3MetadataExtractor) {
        this.resourceServiceClient = resourceServiceClient;
        this.songServiceClient = songServiceClient;
        this.mp3MetadataExtractor = mp3MetadataExtractor;
    }

    @RabbitListener(queues = "${resource.queue}")
    public void handle(Long resourceId) {
        log.info("Received message for resourceId {}", resourceId);
        try {
            byte[] data = resourceServiceClient.getResource(resourceId);
            Mp3Tags tags = mp3MetadataExtractor.extract(data);
            SongMetadataRequest request = new SongMetadataRequest(
                    resourceId, tags.name(), tags.artist(), tags.album(), tags.duration(), tags.year());
            songServiceClient.save(request);
            log.info("Successfully processed resourceId {}", resourceId);
        } catch (InvalidMp3Exception | HttpClientErrorException e) {
            log.error("Permanent failure processing resourceId {}: {}", resourceId, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Permanent failure for resourceId " + resourceId, e);
        }
    }
}
