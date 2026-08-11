package com.inferqueue.api;

import com.inferqueue.domain.ApiKey;
import com.inferqueue.domain.Job;
import com.inferqueue.domain.JobStatus;
import com.inferqueue.security.CurrentApiKey;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Encola un job. Con {@code Idempotency-Key}, repetir el mismo request
     * devuelve 200 con el job original en vez de 201 con uno nuevo, así el
     * cliente puede reintentar un timeout sin duplicar trabajo de inference.
     */
    @PostMapping
    public ResponseEntity<JobResponse> submit(@CurrentApiKey ApiKey apiKey,
                                              @RequestHeader(value = "Idempotency-Key", required = false)
                                              String idempotencyKey,
                                              @Valid @RequestBody SubmitJobRequest request) {
        JobService.Submission submission = jobService.submit(apiKey, request, normalize(idempotencyKey));
        JobResponse body = JobResponse.from(submission.job());
        return submission.replayed()
                ? ResponseEntity.ok(body)
                : ResponseEntity.created(URI.create("/v1/jobs/" + body.id())).body(body);
    }

    private String normalize(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Idempotency-Key no puede superar los " + MAX_IDEMPOTENCY_KEY_LENGTH + " caracteres");
        }
        return trimmed;
    }

    @GetMapping("/{id}")
    public JobResponse get(@CurrentApiKey ApiKey apiKey, @PathVariable UUID id) {
        return JobResponse.from(ownedJob(apiKey, id));
    }

    /**
     * Cancela un job propio. Devuelve 409 si ya había terminado: no es un error
     * del cliente, pero tampoco un no-op silencioso — el resultado ya existe.
     */
    @DeleteMapping("/{id}")
    public JobResponse cancel(@CurrentApiKey ApiKey apiKey, @PathVariable UUID id) {
        Job job = ownedJob(apiKey, id);
        return jobService.cancel(job.getId())
                .map(JobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        "El job ya está en estado " + job.getStatus() + " y no se puede cancelar"));
    }

    private Job ownedJob(ApiKey apiKey, UUID id) {
        Job job = jobService.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Job inexistente"));
        // Una API key sólo ve sus propios jobs.
        if (!job.getApiKeyId().equals(apiKey.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "El job pertenece a otra API key");
        }
        return job;
    }

    @GetMapping
    public Map<String, Object> list(@CurrentApiKey ApiKey apiKey,
                                    @RequestParam(required = false) JobStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Page<Job> jobs = jobService.list(apiKey.getId(), status, page, size);
        List<JobResponse> items = jobs.getContent().stream().map(JobResponse::from).toList();
        return Map.of(
                "items", items,
                "page", jobs.getNumber(),
                "size", jobs.getSize(),
                "total", jobs.getTotalElements());
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@CurrentApiKey ApiKey apiKey) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        return Map.of(
                "apiKeyId", apiKey.getId(),
                "tier", apiKey.getTier(),
                "windowDays", 30,
                "tokensUsed", jobService.tokensUsedSince(apiKey.getId(), since));
    }
}
