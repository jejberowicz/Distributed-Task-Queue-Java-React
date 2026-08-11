package com.inferqueue.worker;

/**
 * Backend de inference. La interfaz existe para que swapear Ollama por otro
 * proveedor (o por un mock en tests) no toque la lógica de la cola.
 */
public interface ModelAdapter {

    /**
     * Ejecuta la inference emitiendo la respuesta por {@code sink} a medida que
     * se genera. Un adapter sin streaming puede ignorar el sink: el resultado
     * completo viaja igual en el {@link InferenceResult} que devuelve.
     */
    InferenceResult infer(InferenceRequest request, TokenSink sink) throws InferenceException;

    String name();
}
