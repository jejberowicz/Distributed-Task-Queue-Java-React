package com.inferqueue.domain;

public enum JobStatus {
    /** Encolado en Redis, todavía no lo tomó ningún worker. */
    QUEUED,
    /** Un worker lo tiene en su PEL y lo está ejecutando. */
    PROCESSING,
    DONE,
    /** Falló pero todavía le quedan reintentos. */
    FAILED,
    /** Superó su TTL antes de completarse. */
    EXPIRED,
    /** Agotó los reintentos y terminó en la dead-letter queue. */
    DEAD,
    /** El cliente lo dio de baja antes de que llegara a completarse. */
    CANCELED;

    public boolean isTerminal() {
        return this == DONE || this == EXPIRED || this == DEAD || this == CANCELED;
    }
}
