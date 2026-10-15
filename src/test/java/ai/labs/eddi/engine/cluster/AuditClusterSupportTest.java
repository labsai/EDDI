/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Cluster audit sequence allocation")
class AuditClusterSupportTest {

    @Test
    @DisplayName("seeds a missing counter from the store once, then hands out consecutive positions")
    void seedsThenIncrements() {
        var kv = new InMemorySharedKv("AUDIT_SEQ", Duration.ofDays(7));
        var support = new AuditClusterSupport(kv, new SimpleMeterRegistry());
        AtomicInteger seedCalls = new AtomicInteger();
        assertEquals(42L, support.nextSequence("conv", () -> {
            seedCalls.incrementAndGet();
            return 42L;
        }).getAsLong());
        assertEquals(43L, support.nextSequence("conv", () -> 0L).getAsLong());
        assertEquals(44L, support.nextSequence("conv", () -> 0L).getAsLong());
        assertEquals(1, seedCalls.get());
    }

    @Test
    @DisplayName("two nodes allocating concurrently never hand out the same position")
    void noReuseAcrossNodes() throws Exception {
        var kv = new InMemorySharedKv("AUDIT_SEQ", Duration.ofDays(7));
        var nodeA = new AuditClusterSupport(kv, new SimpleMeterRegistry());
        var nodeB = new AuditClusterSupport(kv, new SimpleMeterRegistry());
        Set<Long> positions = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger unsequenced = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 400; i++) {
            var node = i % 2 == 0 ? nodeA : nodeB;
            pool.execute(() -> {
                var position = node.nextSequence("conv", () -> 0L);
                if (position.isPresent()) {
                    assertTrue(positions.add(position.getAsLong()), "duplicate position " + position.getAsLong());
                } else {
                    unsequenced.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(400, positions.size() + unsequenced.get());
        assertTrue(positions.size() > 300, "contention degrades only rarely to unsequenced: " + positions.size());
    }

    @Test
    @DisplayName("an unreachable cluster yields no position (recorded unsequenced, never guessed)")
    void unavailableYieldsEmpty() {
        var support = new AuditClusterSupport(new DownSharedKv(), new SimpleMeterRegistry());
        assertTrue(support.nextSequence("conv", () -> 0L).isEmpty());
    }
}
