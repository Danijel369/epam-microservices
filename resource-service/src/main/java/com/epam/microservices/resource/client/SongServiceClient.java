package com.epam.microservices.resource.client;

import com.epam.microservices.resource.exception.DownstreamException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Talks to the Song Service over HTTP using {@link RestClient}. Any error
 * returned by the Song Service is wrapped in a {@link DownstreamException} that
 * preserves the original status and body.
 */
@Component
public class SongServiceClient {

    private final RestClient restClient;

    public SongServiceClient(RestClient songServiceRestClient) {
        this.restClient = songServiceRestClient;
    }

    public void deleteMetadata(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        restClient.delete()
                .uri(uriBuilder -> uriBuilder.path("/songs").queryParam("id", csv).build())
                .retrieve()
                .onStatus(status -> status.isError(), (req, resp) -> {
                    throw new DownstreamException(resp.getStatusCode().value(), readBody(resp));
                })
                .toBodilessEntity();
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
