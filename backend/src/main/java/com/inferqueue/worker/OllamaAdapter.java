package com.inferqueue.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inferqueue.config.InferQueueProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Llama a la API HTTP de Ollama. */
@Component
@ConditionalOnProperty(name = "inferqueue.ollama.adapter", havingValue = "ollama")
public class OllamaAdapter implements ModelAdapter {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public OllamaAdapter(InferQueueProperties props, ObjectMapper objectMapper) {
        this.client = RestClient.builder().baseUrl(props.ollama().baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public InferenceResult infer(InferenceRequest request, TokenSink sink) {
        return switch (request.type()) {
            case COMPLETION, CLASSIFICATION -> generate(request, sink);
            // Un embedding no tiene nada que streamear: sale entero o no sale.
            case EMBEDDING -> embed(request);
        };
    }

    /**
     * Consume la respuesta NDJSON de Ollama con {@code stream: true}: una línea
     * JSON por token, la última con {@code done: true} y los contadores. Se lee
     * a medida que llega en vez de esperar el cuerpo completo — es lo que hace
     * que el dashboard vea la respuesta escribiéndose.
     */
    private InferenceResult generate(InferenceRequest request, TokenSink sink) {
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "prompt", promptFor(request),
                "stream", true);

        StringBuilder output = new StringBuilder();
        int[] tokens = {0};

        try {
            client.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((httpRequest, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new InferenceException(
                                    "Ollama respondió " + response.getStatusCode() + " en /api/generate", true);
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) {
                                    continue;
                                }
                                JsonNode node = objectMapper.readTree(line);
                                String chunk = node.path("response").asText("");
                                if (!chunk.isEmpty()) {
                                    output.append(chunk);
                                    sink.emit(chunk);
                                }
                                if (node.path("done").asBoolean(false)) {
                                    // Ollama reporta los tokens del prompt y de la respuesta por separado.
                                    tokens[0] = node.path("prompt_eval_count").asInt(0)
                                            + node.path("eval_count").asInt(0);
                                }
                            }
                        }
                        return null;
                    });
        } catch (InferenceException e) {
            throw e;
        } catch (RuntimeException e) {
            // Timeouts, cortes de conexión y 5xx son transitorios: vale reintentarlos.
            // El IOException de leer el body llega acá envuelto por el RestClient.
            throw new InferenceException("Ollama falló en /api/generate: " + e.getMessage(), e, true);
        }

        if (output.isEmpty()) {
            throw new InferenceException("Ollama no devolvió respuesta para " + request.model(), true);
        }
        return new InferenceResult(output.toString(), tokens[0]);
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
