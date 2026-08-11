package com.inferqueue.ws;

import com.inferqueue.domain.ApiKey;
import com.inferqueue.security.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Autenticación y autorización del canal STOMP.
 *
 * <p>El handshake HTTP del WebSocket no lleva el header Authorization en todos
 * los clientes, así que la credencial viaja en el frame CONNECT. Ahí se resuelve
 * la API key y se cuelga como {@link Principal} de la sesión; de ahí en más cada
 * SUBSCRIBE se compara contra ese principal.
 *
 * <p>Sin esto el filtrado por API key sería del lado del cliente, que es decir
 * que no hay filtrado: cualquiera con una key válida vería los eventos de todos.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    /** Cada API key tiene su propio árbol de destinos y no puede salirse de él. */
    public static final String KEY_TOPIC_PREFIX = "/topic/keys/";

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    private final ApiKeyService apiKeyService;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public StompAuthInterceptor(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public static String topicFor(Object apiKeyId) {
        return KEY_TOPIC_PREFIX + apiKeyId + "/jobs";
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        return switch (accessor.getCommand()) {
            case CONNECT -> authenticate(message, accessor);
            case SUBSCRIBE -> authorizeSubscription(message, accessor);
            default -> message;
        };
    }

    private Message<?> authenticate(Message<?> message, StompHeaderAccessor accessor) {
        ApiKey apiKey = credential(accessor)
                .flatMap(apiKeyService::authenticate)
                .orElseThrow(() -> {
                    log.warn("CONNECT rechazado: API key inválida o ausente");
                    return new StompAuthenticationException("API key inválida o ausente en el CONNECT");
                });

        accessor.setUser(new ApiKeyPrincipal(apiKey.getId().toString()));
        log.debug("WebSocket autenticado para la API key {}", apiKey.getId());
        return message;
    }

    private Message<?> authorizeSubscription(Message<?> message, StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null) {
            throw new StompAuthenticationException("SUBSCRIBE sin sesión autenticada");
        }
        String destination = accessor.getDestination() == null ? "" : accessor.getDestination();
        // Sólo el subárbol de la propia key: /topic/keys/{id}/jobs y /jobs/{jobId}.
        if (!matcher.match(KEY_TOPIC_PREFIX + user.getName() + "/**", destination)) {
            log.warn("SUBSCRIBE rechazado: la key {} pidió {}", user.getName(), destination);
            throw new StompAuthenticationException("No se puede suscribir a " + destination);
        }
        return message;
    }

    private Optional<String> credential(StompHeaderAccessor accessor) {
        return firstHeader(accessor, "Authorization")
                .map(value -> value.startsWith("Bearer ") ? value.substring(7).trim() : value)
                .or(() -> firstHeader(accessor, "X-API-Key"));
    }

    private Optional<String> firstHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    /** El nombre del principal es el id de la API key: es lo único que necesitamos para autorizar. */
    private record ApiKeyPrincipal(String name) implements Principal {

        @Override
        public String getName() {
            return name;
        }
    }

    /** Hace que el cliente reciba un frame ERROR con el motivo en vez de un cierre mudo. */
    static class StompAuthenticationException extends org.springframework.messaging.MessagingException {

        StompAuthenticationException(String message) {
            super(message);
        }
    }
}
