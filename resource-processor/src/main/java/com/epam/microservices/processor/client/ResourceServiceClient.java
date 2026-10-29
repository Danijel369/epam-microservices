package com.epam.microservices.processor.client;

import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;

@Component
public class ResourceServiceClient {

    private final RestClient restClient;

    public ResourceServiceClient(RestClient resourceServiceRestClient) {
        this.restClient = resourceServiceRestClient;
    }

    // Retries only on transient faults (network/5xx). Effective max = listener 3 × this 2 = 6.
    @Retryable(retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
               maxAttempts = 2, backoff = @Backoff(delay = 500))
    public byte[] getResource(long resourceId) {
        return restClient.get()
                .uri("/resources/{id}", resourceId)
                .accept(MediaType.parseMediaType("audio/mpeg"))
                .retrieve()
                .body(byte[].class);
    }
}
