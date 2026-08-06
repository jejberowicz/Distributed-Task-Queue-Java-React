package com.inferqueue.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferqueue.domain.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Autentica las llamadas a /v1/** con el header Authorization: Bearer iq_xxx
 * y aplica el rate limit del tier antes de dejar pasar el request.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_ATTRIBUTE = "inferqueue.apiKey";

    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<ApiKey> authenticated = extractToken(request).flatMap(apiKeyService::authenticate);
        if (authenticated.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "API key inválida o ausente");
            return;
        }

        ApiKey apiKey = authenticated.get();
        RateLimiter.Decision decision = rateLimiter.check(apiKey.getId(), apiKey.getTier());
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
        if (!decision.allowed()) {
            writeError(response, 429, "Rate limit excedido para el tier " + apiKey.getTier());
            return;
        }

        request.setAttribute(API_KEY_ATTRIBUTE, apiKey);
        chain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7).trim());
        }
        String direct = request.getHeader("X-API-Key");
        return Optional.ofNullable(direct);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }
}
