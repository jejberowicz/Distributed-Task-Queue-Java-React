package com.inferqueue.api;

import com.inferqueue.domain.Tier;
import com.inferqueue.security.ApiKeyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Emisión de API keys. Está fuera de /v1 porque no se autentica con una API key
 * sino con un token de admin; en un sistema real esto viviría detrás del panel
 * de cuentas, no expuesto junto a la API pública.
 */
@RestController
@RequestMapping("/admin/api-keys")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final String adminToken;

    public AdminController(ApiKeyService apiKeyService, @Value("${ADMIN_TOKEN:dev-admin-token}") String adminToken) {
        this.apiKeyService = apiKeyService;
        this.adminToken = adminToken;
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                      @Valid @RequestBody CreateKeyRequest request) {
        if (!adminToken.equals(token)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Token de admin inválido");
        }
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

    public record CreateKeyRequest(@NotBlank String name, UUID userId, Tier tier) {
    }
}
