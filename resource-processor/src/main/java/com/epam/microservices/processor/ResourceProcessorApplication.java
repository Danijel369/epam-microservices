package com.epam.microservices.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Resource Processor. In this module it is a basic, non-web
 * Spring Boot application; MP3 metadata extraction is added next.
 */
@SpringBootApplication
public class ResourceProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResourceProcessorApplication.class, args);
    }
}
