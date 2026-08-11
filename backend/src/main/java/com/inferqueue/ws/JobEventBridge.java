package com.inferqueue.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferqueue.domain.JobEvent;
import com.inferqueue.domain.JobTokenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Los workers pueden correr en un proceso distinto al que tiene abiertos los
 * WebSockets, así que los eventos de job no se pushean directo al broker STOMP:
 * se publican en un canal de Redis pub/sub y cada instancia del gateway los
 * reenvía a sus clientes conectados.
 */
@Component
public class JobEventBridge {

    public static final String CHANNEL = "jobs:events";
    public static final String TOKEN_CHANNEL = "jobs:tokens";

    private static final Logger log = LoggerFactory.getLogger(JobEventBridge.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public JobEventBridge(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onJobEvent(JobEvent event) {
        publish(CHANNEL, event, event.jobId());
    }

    @EventListener
    public void onTokenEvent(JobTokenEvent event) {
        publish(TOKEN_CHANNEL, event, event.jobId());
    }

    private void publish(String channel, Object payload, Object jobId) {
        try {
            redis.convertAndSend(channel, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // Un fallo publicando no debe romper el procesamiento del job:
            // el dashboard se recupera con el polling de /v1/stats.
            log.warn("No se pudo publicar en {} el evento del job {}: {}", channel, jobId, e.toString());
        }
    }
}
