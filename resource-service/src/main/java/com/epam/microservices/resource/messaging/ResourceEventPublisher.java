package com.epam.microservices.resource.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResourceEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String queue;

    public ResourceEventPublisher(RabbitTemplate rabbitTemplate,
                                  @Value("${resource.queue}") String queue) {
        this.rabbitTemplate = rabbitTemplate;
        this.queue = queue;
    }

    public void publish(long resourceId) {
        rabbitTemplate.convertAndSend(queue, resourceId);
    }
}
