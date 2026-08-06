package com.inferqueue.worker;

/** Falla al ejecutar la inference. {@code retryable} decide si vale reintentar. */
public class InferenceException extends RuntimeException {

    private final boolean retryable;

    public InferenceException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public InferenceException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
