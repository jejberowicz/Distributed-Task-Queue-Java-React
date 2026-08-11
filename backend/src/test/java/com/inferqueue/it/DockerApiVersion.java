package com.inferqueue.it;

import java.util.concurrent.TimeUnit;

/**
 * Alinea la versión de la API de Docker que usa Testcontainers con la que
 * realmente habla el demonio.
 *
 * <p>El cliente que trae Testcontainers negocia 1.41 por defecto, y Docker
 * Engine 29 dejó de aceptar todo lo anterior a 1.44: el ping de detección
 * responde 400 y Testcontainers concluye que no hay Docker instalado. Fijar un
 * número a mano resuelve esa máquina y rompe la siguiente — quien tenga un
 * Docker más viejo que el número elegido queda afuera.
 *
 * <p>Así que en vez de elegir, preguntamos: {@code docker version} dice qué
 * versión de API expone el demonio y usamos esa. Si algo no sale como se espera
 * —no hay CLI, no responde, formato raro— no tocamos nada y dejamos que
 * Testcontainers haga lo suyo, que es el comportamiento que tenía antes.
 */
final class DockerApiVersion {

    /** La propiedad que lee docker-java para saber contra qué versión hablar. */
    private static final String API_VERSION_PROPERTY = "api.version";

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private DockerApiVersion() {
    }

    static void alignWithDaemon() {
        // Si alguien la fijó explícitamente (property de Maven, DOCKER_API_VERSION
        // en el entorno), esa decisión gana: acá no adivinamos por encima de nadie.
        if (System.getProperty(API_VERSION_PROPERTY) != null || System.getenv("DOCKER_API_VERSION") != null) {
            return;
        }
        detectFromDaemon().ifPresent(version -> System.setProperty(API_VERSION_PROPERTY, version));
    }

    private static java.util.Optional<String> detectFromDaemon() {
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "version", "--format", "{{.Server.APIVersion}}")
                    .redirectErrorStream(false)
                    .start();

            String output;
            try (var reader = process.inputReader()) {
                output = reader.readLine();
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return java.util.Optional.empty();
            }
            // Esperamos algo como "1.52". Cualquier otra cosa la ignoramos en vez
            // de pasársela al cliente y que falle de una forma más confusa.
            return output != null && output.matches("\\d+\\.\\d+")
                    ? java.util.Optional.of(output.trim())
                    : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
