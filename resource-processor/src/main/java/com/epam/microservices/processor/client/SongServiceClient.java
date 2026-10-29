package com.epam.microservices.processor.client;

import com.epam.microservices.processor.dto.SongMetadataRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class SongServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SongServiceClient.class);

    private final RestClient restClient;

    public SongServiceClient(RestClient songServiceRestClient) {
        this.restClient = songServiceRestClient;
    }

    // Retries only on transient faults (network/5xx). Effective max = listener 3 × this 2 = 6.
    @Retryable(retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
               maxAttempts = 2, backoff = @Backoff(delay = 500))
    public void save(SongMetadataRequest request) {
        restClient.post()
                .uri("/songs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> status.value() == 409, (req, resp) ->
                        log.info("Song already exists for resourceId {}, treating as success (idempotent)", request.id()))
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw new HttpClientErrorException(resp.getStatusCode());
                })
                .toBodilessEntity();
    }
}
