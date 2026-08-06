package com.inferqueue.security;

import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.ApiKeyRepository;
import com.inferqueue.domain.Tier;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyService service = new ApiKeyService(repository);

    @Test
    void elKeyEnClaroNuncaSeGuarda() {
        when(repository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyService.IssuedKey issued = service.issue(UUID.randomUUID(), "cli", Tier.PREMIUM);

        assertThat(issued.plaintextKey()).startsWith("iq_");
        assertThat(issued.tier()).isEqualTo(Tier.PREMIUM);
        // Lo persistido es el hash, no el key.
        assertThat(ApiKeyService.hash(issued.plaintextKey())).isNotEqualTo(issued.plaintextKey());
    }

    @Test
    void dosKeysConsecutivosSonDistintos() {
        when(repository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String first = service.issue(UUID.randomUUID(), "a", Tier.FREE).plaintextKey();
        String second = service.issue(UUID.randomUUID(), "b", Tier.FREE).plaintextKey();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void autenticaBuscandoPorHash() {
        ApiKey stored = new ApiKey(UUID.randomUUID(), "cli", ApiKeyService.hash("iq_secreto"), Tier.FREE);
        when(repository.findByKeyHashAndRevokedFalse(ApiKeyService.hash("iq_secreto")))
                .thenReturn(Optional.of(stored));

        assertThat(service.authenticate("iq_secreto")).contains(stored);
        assertThat(service.authenticate("iq_otro")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("  ")).isEmpty();
    }
}
