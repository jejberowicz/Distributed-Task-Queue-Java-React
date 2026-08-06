package com.inferqueue.worker;

/**
 * Backend de inference. La interfaz existe para que swapear Ollama por otro
 * proveedor (o por un mock en tests) no toque la lógica de la cola.
 */
public interface ModelAdapter {

    InferenceResult infer(InferenceRequest request) throws InferenceException;

    String name();
}
