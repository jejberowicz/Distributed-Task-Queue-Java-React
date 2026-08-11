package com.inferqueue.api;

import com.inferqueue.domain.Priority;
import com.inferqueue.queue.DelayedQueue;
import com.inferqueue.queue.JobQueue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Estado agregado de la cola para el dashboard. */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final JobService jobService;
    private final JobQueue queue;
    private final DelayedQueue delayedQueue;

    public StatsController(JobService jobService, JobQueue queue, DelayedQueue delayedQueue) {
        this.jobService = jobService;
        this.queue = queue;
        this.delayedQueue = delayedQueue;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        jobService.statusCounts().forEach((status, count) -> byStatus.put(status.name(), count));

        Map<String, Object> streams = new LinkedHashMap<>();
        streams.put("priorityDepth", queue.depth(Priority.PRIORITY));
        streams.put("standardDepth", queue.depth(Priority.STANDARD));
        streams.put("priorityPending", queue.pendingCount(Priority.PRIORITY));
        streams.put("standardPending", queue.pendingCount(Priority.STANDARD));
        streams.put("deadLetterDepth", queue.deadLetterDepth());
        streams.put("delayedRetries", delayedQueue.size());

        return Map.of("byStatus", byStatus, "streams", streams);
    }
}
