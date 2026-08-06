package com.inferqueue.api;

import com.inferqueue.domain.JobType;
import com.inferqueue.domain.Priority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitJobRequest(
        @NotBlank String model,
        JobType type,
        @NotBlank @Size(max = 32_000) String prompt,
        /* Si no se manda, se usa la prioridad por defecto del tier de la API key. */
        Priority priority,
        @Min(5) @Max(86_400) Integer ttlSeconds
) {
}
