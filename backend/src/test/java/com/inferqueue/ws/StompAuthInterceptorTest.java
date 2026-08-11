package com.inferqueue.ws;

import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Tier;
import com.inferqueue.security.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompAuthInterceptorTest {

    private static final String PLAINTEXT = "iq_secreto";

    private final ApiKey apiKey = new ApiKey(UUID.randomUUID(), "dashboard", "hash", Tier.PREMIUM);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final StompAuthInterceptor interceptor = new StompAuthInterceptor(apiKeyService);
    private final MessageChannel channel = mock(MessageChannel.class);

    private StompHeaderAccessor accessor(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("el CONNECT con una key válida deja la sesión asociada a esa API key")
    void connectBindsPrincipal() {
        when(apiKeyService.authenticate(PLAINTEXT)).thenReturn(Optional.of(apiKey));
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + PLAINTEXT);

        interceptor.preSend(message(accessor), channel);

        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo(apiKey.getId().toString());
    }

    @Test
    @DisplayName("un CONNECT sin credencial se rechaza en vez de quedar como sesión anónima")
    void connectWithoutCredentialIsRejected() {
        Message<byte[]> message = message(accessor(StompCommand.CONNECT));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .hasMessageContaining("API key inválida o ausente");
    }

    @Test
    @DisplayName("un CONNECT con una key desconocida se rechaza")
    void connectWithUnknownKeyIsRejected() {
        when(apiKeyService.authenticate("iq_falso")).thenReturn(Optional.empty());
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer iq_falso");
        Message<byte[]> message = message(accessor);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .hasMessageContaining("API key inválida o ausente");
    }

    @Test
    @DisplayName("cada key sólo se puede suscribir a su propio topic")
    void subscriptionIsScopedToOwnTopic() {
        Principal owner = apiKey.getId()::toString;

        assertThat(subscribe(owner, StompAuthInterceptor.topicFor(apiKey.getId()))).isNotNull();
        assertThat(subscribe(owner, StompAuthInterceptor.topicFor(apiKey.getId()) + "/" + UUID.randomUUID()))
                .isNotNull();
    }

    @Test
    @DisplayName("suscribirse al topic de otra API key se rechaza")
    void subscriptionToForeignTopicIsRejected() {
        Principal owner = apiKey.getId()::toString;
        String foreign = StompAuthInterceptor.topicFor(UUID.randomUUID());

        assertThatThrownBy(() -> subscribe(owner, foreign))
                .hasMessageContaining("No se puede suscribir");
    }

    @Test
    @DisplayName("el topic global heredado ya no es suscribible")
    void globalTopicIsRejected() {
        Principal owner = apiKey.getId()::toString;

        assertThatThrownBy(() -> subscribe(owner, "/topic/jobs"))
                .hasMessageContaining("No se puede suscribir");
    }

    @Test
    @DisplayName("un SUBSCRIBE sin CONNECT previo se rechaza")
    void subscriptionWithoutSessionIsRejected() {
        assertThatThrownBy(() -> subscribe(null, StompAuthInterceptor.topicFor(apiKey.getId())))
                .hasMessageContaining("sin sesión autenticada");
    }

    private Message<?> subscribe(Principal user, String destination) {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE);
        accessor.setUser(user);
        accessor.setDestination(destination);
        return interceptor.preSend(message(accessor), channel);
    }
}
