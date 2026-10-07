package com.jobber.core.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology shared by the API (publisher) and worker (consumer). Both apps declare it on
 * startup; declarations are idempotent, so whichever starts first creates it.
 */
@Configuration(proxyBeanMethods = false)
public class JobQueueConfig {

    public static final String EXCHANGE = "jobber.jobs";
    public static final String QUEUE = "jobber.jobs.ready";
    public static final String ROUTING_KEY = "ready";

    /**
     * Where RabbitMQ sends messages a worker rejects: unreadable messages, messages for jobs that
     * don't exist, or messages whose outcome could not be recorded. This is for message failures
     * only; a job that fails is recorded as FAILED in Postgres, not dead-lettered.
     */
    public static final String DEAD_LETTER_EXCHANGE = "jobber.jobs.dlx";
    public static final String DEAD_LETTER_QUEUE = "jobber.jobs.dead";
    public static final String DEAD_LETTER_ROUTING_KEY = "dead";

    @Bean
    DirectExchange jobsExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /**
     * Durable, so queued messages survive a broker restart. Rejected messages are dead-lettered.
     * Queue arguments cannot change after creation: changing them requires deleting the queue.
     */
    @Bean
    Queue readyJobsQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding readyJobsBinding(Queue readyJobsQueue, DirectExchange jobsExchange) {
        return BindingBuilder.bind(readyJobsQueue).to(jobsExchange).with(ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * Messages travel as JSON instead of Java serialization. Spring Boot applies this to templates and listeners.
     * The converter picks the target class from a message header, so only our own message package is
     * trusted; a crafted message cannot make us instantiate arbitrary classes.
     */
    @Bean
    MessageConverter jobMessageConverter() {
        return new JacksonJsonMessageConverter(JobMessage.class.getPackageName());
    }
}
