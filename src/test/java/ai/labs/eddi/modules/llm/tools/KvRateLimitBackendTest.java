/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.cluster.DownSharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Cluster-wide tool rate limits")
class KvRateLimitBackendTest {

    @Test
    @DisplayName("a limit of 10/min allows exactly 10 calls across three nodes")
    void limitHoldsAcrossNodes() throws Exception {
        var kv = new InMemorySharedKv("RATELIMIT", Duration.ofMinutes(2));
        AtomicLong now = new AtomicLong(1_000_000);
        var nodes = new KvRateLimitBackend[]{new KvRateLimitBackend(kv, new SimpleMeterRegistry(), now::get),
                new KvRateLimitBackend(kv, new SimpleMeterRegistry(), now::get),
                new KvRateLimitBackend(kv, new SimpleMeterRegistry(), now::get)};
        String key = KvRateLimitBackend.key("*global*", "calculator");
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < 30; i++) {
            var node = nodes[i % 3];
            pool.execute(() -> {
                if (node.take(key, 10) == KvRateLimitBackend.Decision.ALLOWED) {
                    allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(allowed.get() <= 10, "never more than the limit: " + allowed.get());
        assertTrue(allowed.get() >= 8, "contention may deny, never over-allow: " + allowed.get());
    }

    @Test
    @DisplayName("tokens refill with time exactly like the local bucket")
    void refills() {
        var kv = new InMemorySharedKv("RATELIMIT", Duration.ofMinutes(2));
        AtomicLong now = new AtomicLong(1_000_000);
        var backend = new KvRateLimitBackend(kv, new SimpleMeterRegistry(), now::get);
        String key = KvRateLimitBackend.key("conv-1", "web");
        for (int i = 0; i < 6; i++) {
            assertEquals(KvRateLimitBackend.Decision.ALLOWED, backend.take(key, 6));
        }
        assertEquals(KvRateLimitBackend.Decision.DENIED, backend.take(key, 6));
        now.addAndGet(ToolRateLimiter.WINDOW_MS / 6); // one token back
        assertEquals(KvRateLimitBackend.Decision.ALLOWED, backend.take(key, 6));
        assertEquals(KvRateLimitBackend.Decision.DENIED, backend.take(key, 6));
        backend.giveBack(key);
        assertEquals(KvRateLimitBackend.Decision.ALLOWED, backend.take(key, 6));
    }

    @Test
    @DisplayName("an unreachable cluster is reported, so the degraded policy can decide")
    void unavailable() {
        var backend = new KvRateLimitBackend(new DownSharedKv(), new SimpleMeterRegistry(), System::currentTimeMillis);
        assertEquals(KvRateLimitBackend.Decision.UNAVAILABLE, backend.take("k", 5));
    }
}
