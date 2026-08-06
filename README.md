# InferQueue

Plataforma de AI inference asíncrona: submitís un job de inference por REST, se encola en
Redis Streams, un pool de workers lo procesa contra Ollama, y el resultado llega al dashboard
en tiempo real por WebSocket.

Stack: Java 21 + Spring Boot 3, Redis Streams, PostgreSQL, React + Vite, STOMP/WebSocket, Docker Compose.

> Documentación de arquitectura y decisiones de diseño: ver el final de este README.
