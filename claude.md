Un sistema tipo mini-Celery o mini-Bull:

API REST en Spring Boot para submitear jobs
Workers que consumen jobs de una cola (podés usar Redis Streams o implementar la cola vos mismo)
Reintentos con backoff exponencial, dead-letter queue, TTL por job
Dashboard en React con estado de jobs en tiempo real vía WebSockets
Todo dockerizado con docker-compose

Lo que lo hace técnicamente impresionante es mostrar que pensaste en los edge cases: ¿qué pasa si un worker muere a mitad de un job? ¿cómo garantizás at-least-once delivery? Documentar esas decisiones en el README vale oro.
Stack: Java 17 + Spring Boot, Redis, PostgreSQL, React, WebSockets, Docker Compose.



Un sistema tipo mini-Celery o mini-Bull:

API REST en Spring Boot para submitear jobs
Workers que consumen jobs de una cola (podés usar Redis Streams o implementar la cola vos mismo)
Reintentos con backoff exponencial, dead-letter queue, TTL por job
Dashboard en React con estado de jobs en tiempo real vía WebSockets
Todo dockerizado con docker-compose Lo que lo hace técnicamente impresionante es mostrar que pensaste en los edge cases: ¿qué pasa si un worker muere a mitad de un job? ¿cómo garantizás at-least-once delivery? Documentar esas decisiones en el README vale oro. Stack: Java 17 + Spring Boot, Redis, PostgreSQL, React, WebSockets, Docker Compose.
esto lo podemos haceru n trabajo mas completo como que sea para un tipo dep agina en particular con una logica de negocio en particular?

Mostrar menos
5:27 PM

Evaluó dominios específicos para enriquecer el proyecto con contexto empresarial realista
Evaluó dominios específicos para enriquecer el proyecto con contexto empresarial realista
Sí, es mucho mejor así — un proyecto genérico de "task queue" es aburrido, pero "una plataforma de X que usa una task queue bajo el capó" cuenta una historia. Le da contexto, justifica las decisiones técnicas, y es más fácil de mostrar en una entrevista.

La pregunta clave es: ¿qué dominio elegir? Te presento cuatro opciones concretas, cada una con un ángulo diferente:

P: ¿Qué dominio te parece más interesante para el proyecto?
R: Plataforma de inference AI — queue de requests a modelos

5:29 PM

Architected comprehensive AI inference queue platform with Redis, Spring Boot, and PostgreSQL
Architected comprehensive AI inference queue platform with Redis, Spring Boot, and PostgreSQL
Excelente elección. Una plataforma de AI inference queue es el proyecto más relevante del 2026 y te diferencia enormemente: combina arquitectura distribuida real con el tema más caliente de la industria. Es básicamente lo que hacen Replicate, Together.ai y Modal internamente.

El concepto: una plataforma donde cualquiera puede submitear jobs de inference (texto, embeddings, clasificación) via REST API, los jobs se procesan asincrónicamente por workers, y el resultado llega en tiempo real. Y como tenés Ollama con tu GPU, puede funcionar con modelos reales.

Así queda la arquitectura:



(Cada componente es clickeable — podés preguntarme cómo implementar cualquiera.)

El nombre: InferQueue
Tres capas técnicas que lo hacen impresionante por separado, y juntas cuentan una historia coherente:

Capa 1 — API Gateway (Spring Boot)

El gateway hace tres cosas no triviales: autenticación via API keys (un hash en PostgreSQL, no JWT hardcodeado), rate limiting por key (usando un contador en Redis con TTL), y enqueue con prioridad. Los clientes premium van al stream infer:priority, los free al infer:standard. El gateway también mantiene el WebSocket abierto hacia el dashboard para pushear actualizaciones de estado.

Capa 2 — Redis Streams como queue

Acá está el corazón técnico del proyecto. Redis Streams con consumer groups te da at-least-once delivery gratis: usás XREADGROUP para consumir, y el mensaje queda en el Pending Entry List (PEL) del worker hasta que hace XACK. Si el worker muere, el mensaje queda ahí sin ACK. El mecanismo de recovery: un thread recorre periódicamente la PEL buscando mensajes con más de N segundos sin ACK y los reclama con XCLAIM para otro worker. Eso es exactamente cómo lo hace Redis en producción. El DLQ es otro stream (infer:dlq) al que van los jobs que fallaron más de 3 veces.

Capa 3 — Workers + Ollama

Los workers son Spring Boot beans que corren en threads separados (o podés usar virtual threads de Java 21, que es otro punto a favor). Cada worker tiene un ModelAdapter interface con dos implementaciones: OllamaAdapter (que llama a la API HTTP de Ollama en localhost) y MockAdapter (para tests). Cuando termina el job, escribe el resultado en PostgreSQL y hace XACK. El dashboard se entera via WebSocket porque el worker publica en un topic STOMP.

El PostgreSQL schema que muestra madurez
sql
CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    key_hash TEXT NOT NULL,   -- nunca el key en claro
    tier TEXT NOT NULL,        -- 'free' | 'premium'
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    api_key_id UUID REFERENCES api_keys(id),
    model TEXT NOT NULL,
    prompt TEXT NOT NULL,
    status TEXT NOT NULL,      -- queued | processing | done | failed
    result TEXT,
    tokens_used INT,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);
El tokens_used por job + el tier en la API key te da una feature de usage tracking que cualquier empresa reconoce como real.

Lo que hace que esto sea diferente de un tutorial
La sección "Design decisions" del README es lo que convierte el proyecto en un diferenciador. Cuatro preguntas que respondés explícitamente:

¿Por qué Redis Streams y no RabbitMQ/Kafka? Streams da at-least-once con PEL sin overhead de deploy. Para un proyecto personal con workers locales, es la elección correcta.
¿Cómo garantizás que un job no se pierde si el worker muere? XCLAIM con timeout. Describís el race condition (dos workers pueden reclamar el mismo job si el timeout es muy agresivo) y cómo lo mitigás.
¿Por qué priority queue sobre FIFO simple? Los modelos grandes (llama3:70b) tardan más — un job de embedding no debería esperar 5 minutos por un job de 8k tokens.
¿Por qué Ollama y no llamar a OpenAI? Costo cero en dev, reproducible, y el ModelAdapter interface hace que swapear backends sea trivial.
