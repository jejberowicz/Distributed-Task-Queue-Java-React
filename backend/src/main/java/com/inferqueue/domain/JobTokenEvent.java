package com.inferqueue.domain;

import java.util.UUID;

/**
 * Fragmento de la respuesta de un job en curso. Va por un canal aparte del
 * {@link JobEvent} a propósito: son muchos y efímeros, y perder uno no cambia el
 * estado del job — el resultado definitivo siempre llega en el evento de DONE.
 */
public record JobTokenEvent(UUID jobId, UUID apiKeyId, int seq, String chunk) {
}
