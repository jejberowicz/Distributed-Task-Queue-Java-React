package com.inferqueue.api;

import com.inferqueue.domain.Tier;
import com.inferqueue.queue.DeadLetterEntry;
import com.inferqueue.security.AdminTokenGuard;
import com.inferqueue.security.ApiKeyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Endpoints de operación: emisión de API keys y manejo de la DLQ. Están fuera de
 * /v1 porque no se autentican con una API key sino con un token de admin; en un
 * sistema real vivirían detrás del panel de cuentas, no junto a la API pública.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final DeadLetterService deadLetters;
    private final AdminTokenGuard guard;

    public AdminController(ApiKeyService apiKeyService, DeadLetterService deadLetters, AdminTokenGuard guard) {
        this.apiKeyService = apiKeyService;
        this.deadLetters = deadLetters;
        this.guard = guard;
    }

    @PostMapping("/api-keys")
    public Map<String, Object> createKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                         @Valid @RequestBody CreateKeyRequest request) {
        guard.require(token);
        UUID userId = request.userId() != null ? request.userId() : UUID.randomUUID();
        Tier tier = request.tier() != null ? request.tier() : Tier.FREE;
        ApiKeyService.IssuedKey issued = apiKeyService.issue(userId, request.name(), tier);
        // El plaintext se devuelve una sola vez: en la base sólo queda el hash.
        return Map.of(
                "id", issued.id(),
                "userId", userId,
                "tier", issued.tier(),
                "apiKey", issued.plaintextKey());
    }

    /** Qué murió y por qué. Es lo primero que se mira cuando la DLQ empieza a crecer. */
    @GetMapping("/dlq")
    public Map<String, Object> listDeadLetters(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                               @RequestParam(defaultValue = "50") int limit) {
        guard.require(token);
        List<DeadLetterEntry> entries = deadLetters.list(limit);
        return Map.of("items", entries, "count", entries.size());
    }

    /** Reencola un job muerto con los intentos y el TTL en cero. */
    @PostMapping("/dlq/{recordId}/requeue")
    public JobResponse requeue(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                               @PathVariable String recordId) {
        guard.require(token);
        return deadLetters.requeue(recordId)
                .map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "La entrada de DLQ no existe o el job ya volvió a la cola"));
    }

    @DeleteMapping("/dlq/{recordId}")
    public Map<String, Object> discard(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                       @PathVariable String recordId) {
        guard.require(token);
        return Map.of("discarded", deadLetters.discard(recordId));
    }

    @DeleteMapping("/dlq")
    public Map<String, Object> purge(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        guard.require(token);
        return Map.of("purged", deadLetters.purge());
    }

    public record CreateKeyRequest(@NotBlank String name, UUID userId, Tier tier) {
    }
}
