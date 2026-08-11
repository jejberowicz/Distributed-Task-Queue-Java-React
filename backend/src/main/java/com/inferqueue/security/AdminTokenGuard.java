package com.inferqueue.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Puerta de los endpoints /admin/**. En un sistema real esto sería el panel de
 * cuentas con su propia sesión; acá es un token compartido, pero al menos se
 * compara en tiempo constante para no filtrarlo carácter por carácter.
 */
@Component
public class AdminTokenGuard {

    private final byte[] expected;

    public AdminTokenGuard(@Value("${ADMIN_TOKEN:dev-admin-token}") String adminToken) {
        this.expected = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String token) {
        if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(UNAUTHORIZED, "Token de admin inválido");
        }
    }
}
