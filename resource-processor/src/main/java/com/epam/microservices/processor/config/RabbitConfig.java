package com.epam.microservices.processor.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

@Configuration
public class RabbitConfig {

    @Bean
    public Queue resourceQueue(@Value("${resource.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitRetryTemplateCustomizer listenerRetryPolicyCustomizer(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts) {
        return (target, retryTemplate) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                // AmqpRejectAndDontRequeueException signals a permanent failure (D1).
                // traverseCauses=true handles it when wrapped in ListenerExecutionFailedException.
                retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                        maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false),
                        true,  // traverseCauses
                        true   // defaultValue: retry everything else
                ));
            }
        };
    }
}
