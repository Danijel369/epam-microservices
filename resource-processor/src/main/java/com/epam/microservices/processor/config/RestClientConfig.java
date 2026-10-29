package com.epam.microservices.processor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(
            @Value("${http.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${http.client.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public RestClient resourceServiceRestClient(@LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
                                                @Value("${resource-service.base-url}") String baseUrl) {
        return loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient songServiceRestClient(@LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
                                            @Value("${song-service.base-url}") String baseUrl) {
        return loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }
}
