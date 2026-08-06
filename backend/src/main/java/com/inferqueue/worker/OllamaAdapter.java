package com.inferqueue.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.inferqueue.config.InferQueueProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** Llama a la API HTTP de Ollama. */
@Component
@ConditionalOnProperty(name = "inferqueue.ollama.adapter", havingValue = "ollama")
public class OllamaAdapter implements ModelAdapter {

    private final RestClient client;

    public OllamaAdapter(InferQueueProperties props) {
        this.client = RestClient.builder().baseUrl(props.ollama().baseUrl()).build();
    }

    @Override
    public InferenceResult infer(InferenceRequest request) {
        return switch (request.type()) {
            case COMPLETION, CLASSIFICATION -> generate(request);
            case EMBEDDING -> embed(request);
        };
    }

    private InferenceResult generate(InferenceRequest request) {
        JsonNode response = post("/api/generate", Map.of(
                "model", request.model(),
                "prompt", promptFor(request),
                "stream", false));
        String output = response.path("response").asText();
        // Ollama reporta los tokens del prompt y de la respuesta por separado.
        int tokens = response.path("prompt_eval_count").asInt(0) + response.path("eval_count").asInt(0);
        return new InferenceResult(output, tokens);
    }

    private InferenceResult embed(InferenceRequest request) {
        JsonNode response = post("/api/embeddings", Map.of(
                "model", request.model(),
                "prompt", request.prompt()));
        JsonNode embedding = response.path("embedding");
        if (!embedding.isArray()) {
            throw new InferenceException("Ollama no devolvió un embedding para " + request.model(), false);
        }
        return new InferenceResult(embedding.toString(), embedding.size());
    }

    private String promptFor(InferenceRequest request) {
        if (request.type() == com.inferqueue.domain.JobType.CLASSIFICATION) {
            return """
                    Clasificá el siguiente texto y respondé únicamente con la etiqueta, sin explicación.

                    %s""".formatted(request.prompt());
        }
        return request.prompt();
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new InferenceException("Respuesta vacía de Ollama en " + path, true);
            }
            return response;
        } catch (RestClientException e) {
            // Timeouts y 5xx son transitorios: vale la pena reintentarlos.
            throw new InferenceException("Ollama falló en " + path + ": " + e.getMessage(), e, true);
        }
    }

    @Override
    public String name() {
        return "ollama";
    }
}
