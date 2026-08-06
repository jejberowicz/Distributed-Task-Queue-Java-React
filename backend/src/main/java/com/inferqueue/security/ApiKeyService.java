package com.inferqueue.security;

import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.ApiKeyRepository;
import com.inferqueue.domain.Tier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final String PREFIX = "iq_";
    private static final int KEY_BYTES = 24;

    private final ApiKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Genera un key nuevo. El valor en claro se devuelve una única vez —
     * en la base sólo queda el hash.
     */
    @Transactional
    public IssuedKey issue(UUID userId, String name, Tier tier) {
        byte[] buf = new byte[KEY_BYTES];
        random.nextBytes(buf);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        ApiKey saved = repository.save(new ApiKey(userId, name, hash(plaintext), tier));
        return new IssuedKey(saved.getId(), plaintext, saved.getTier());
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticate(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByKeyHashAndRevokedFalse(hash(plaintextKey));
    }

    static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record IssuedKey(UUID id, String plaintextKey, Tier tier) {
    }
}
