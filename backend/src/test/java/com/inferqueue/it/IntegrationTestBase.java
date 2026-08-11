package com.inferqueue.it;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de los tests de integración: Postgres y Redis reales en contenedores.
 *
 * <p>Existen porque hay comportamiento que no se puede testear con mocks sin
 * testear el mock: el PEL de un consumer group, qué exactamente reclama XCLAIM,
 * si el índice único de idempotencia realmente frena dos inserts simultáneos, o
 * si el SELECT ... FOR UPDATE serializa las transiciones de estado.
 *
 * <p>Los contenedores son estáticos y se comparten entre todas las clases que
 * heredan de acá: arrancarlos cuesta segundos y el estado se aísla por job, no
 * por base limpia.
 */
@Testcontainers
@ActiveProfiles("integration")
public abstract class IntegrationTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
}
