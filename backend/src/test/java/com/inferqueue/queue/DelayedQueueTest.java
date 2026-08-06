package com.inferqueue.queue;

import com.inferqueue.TestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DelayedQueueTest {

    private final DelayedQueue delayedQueue = new DelayedQueue(
            mock(StringRedisTemplate.class), mock(JobQueue.class), TestProperties.defaults());

    @Test
    void backoffCreceExponencialmenteDesdeLaBase() {
        assertThat(delayedQueue.backoffFor(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(delayedQueue.backoffFor(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(delayedQueue.backoffFor(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(delayedQueue.backoffFor(3)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void backoffNuncaSuperaElTecho() {
        assertThat(delayedQueue.backoffFor(20)).isEqualTo(Duration.ofMinutes(5));
        assertThat(delayedQueue.backoffFor(1000)).isEqualTo(Duration.ofMinutes(5));
    }
}
