package com.inferqueue.worker;

/**
 * Recibe la respuesta del modelo a medida que se genera. Los adapters que no
 * soportan streaming simplemente no lo llaman: el resultado final llega igual
 * por {@link InferenceResult}, el sink es un extra para la UI.
 */
@FunctionalInterface
public interface TokenSink {

    TokenSink NOOP = chunk -> {
    };

    void emit(String chunk);
}
