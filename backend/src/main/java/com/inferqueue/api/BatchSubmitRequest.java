package com.inferqueue.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Lote de jobs en un solo request. El límite existe porque el lote entra en una
 * única transacción: un batch enorme sostiene el lock de la tabla y demora el
 * commit, que es justo lo que no queremos en el camino de encolado.
 */
public record BatchSubmitRequest(
        @NotEmpty(message = "el lote no puede estar vacío")
        @Size(max = MAX_BATCH_SIZE, message = "el lote no puede tener más de " + MAX_BATCH_SIZE + " jobs")
        @Valid List<SubmitJobRequest> jobs
) {

    public static final int MAX_BATCH_SIZE = 100;
}
