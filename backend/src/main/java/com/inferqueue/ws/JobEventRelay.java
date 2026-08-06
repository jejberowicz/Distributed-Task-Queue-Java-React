package com.inferqueue.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferqueue.domain.JobEvent;
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
            messaging.convertAndSend("/topic/jobs", event);
            // Topic por job para que un cliente pueda seguir uno solo sin recibir todo.
            messaging.convertAndSend("/topic/jobs/" + event.jobId(), event);
        } catch (Exception e) {
            log.warn("Evento de job ilegible: {}", e.toString());
        }
    }

    @Configuration
    static class RelaySubscription {

        @Bean
        RedisMessageListenerContainer jobEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                JobEventRelay relay) {
            MessageListenerAdapter adapter = new MessageListenerAdapter(relay, "handleMessage");
            adapter.afterPropertiesSet();
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(adapter, new ChannelTopic(JobEventBridge.CHANNEL));
            return container;
        }
    }
}
