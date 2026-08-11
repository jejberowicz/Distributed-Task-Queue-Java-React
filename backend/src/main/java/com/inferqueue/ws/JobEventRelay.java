package com.inferqueue.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobTokenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Toma los eventos del canal de Redis y los empuja a los topics STOMP. */
@Component
public class JobEventRelay {

    private static final Logger log = LoggerFactory.getLogger(JobEventRelay.class);

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    public JobEventRelay(SimpMessagingTemplate messaging, ObjectMapper objectMapper) {
        this.messaging = messaging;
        this.objectMapper = objectMapper;
    }

    /** Invocado por el MessageListenerAdapter con el payload del canal. */
    public void handleMessage(String payload) {
        try {
            JobEvent event = objectMapper.readValue(payload, JobEvent.class);
            // Un topic por API key, no uno global: el aislamiento entre tenants lo
            // hace el broker, no el cliente. Ver StompAuthInterceptor.
            String topic = StompAuthInterceptor.topicFor(event.apiKeyId());
            messaging.convertAndSend(topic, event);
            // Topic por job para que un cliente pueda seguir uno solo sin recibir todo.
            messaging.convertAndSend(topic + "/" + event.jobId(), event);
        } catch (Exception e) {
            log.warn("Evento de job ilegible: {}", e.toString());
        }
    }

    /** Invocado con el payload del canal de tokens. */
    public void handleTokenMessage(String payload) {
        try {
            JobTokenEvent event = objectMapper.readValue(payload, JobTokenEvent.class);
            // Un solo topic por key para todos los jobs: el cliente ya sabe a qué
            // job pertenece cada fragmento y así no hay que suscribirse por job.
            messaging.convertAndSend(StompAuthInterceptor.topicFor(event.apiKeyId()) + "/tokens", event);
        } catch (Exception e) {
            log.warn("Fragmento de tokens ilegible: {}", e.toString());
        }
    }

    @Configuration
    static class RelaySubscription {

        @Bean
        RedisMessageListenerContainer jobEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                JobEventRelay relay) {
            MessageListenerAdapter events = new MessageListenerAdapter(relay, "handleMessage");
            events.afterPropertiesSet();
            MessageListenerAdapter tokens = new MessageListenerAdapter(relay, "handleTokenMessage");
            tokens.afterPropertiesSet();

            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(events, new ChannelTopic(JobEventBridge.CHANNEL));
            container.addMessageListener(tokens, new ChannelTopic(JobEventBridge.TOKEN_CHANNEL));
            return container;
        }
    }
}
