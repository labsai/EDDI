/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double with the store contract's atomicity (one lock around check and
 * increment) and switches to simulate an outage and to count store round trips.
 */
public class InMemoryToolQuotaStore implements IToolQuotaStore {

    private final Map<ToolQuotaKey, Integer> counts = new HashMap<>();
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean failing;

    private void check() {
        calls.incrementAndGet();
        if (failing) {
            throw new ToolQuotaStoreException("simulated outage", new RuntimeException("down"));
        }
    }

    @Override
    public synchronized Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt) {
        check();
        int used = counts.getOrDefault(key, 0);
        if (used >= limit) {
            return new Acquisition(false, used);
        }
        counts.put(key, used + 1);
        return new Acquisition(true, used + 1);
    }

    @Override
    public synchronized void release(ToolQuotaKey key) {
        check();
        counts.computeIfPresent(key, (k, v) -> Math.max(0, v - 1));
    }

    @Override
    public synchronized int used(ToolQuotaKey key) {
        check();
        return counts.getOrDefault(key, 0);
    }

    public synchronized void set(ToolQuotaKey key, int used) {
        counts.put(key, used);
    }

    @Override
    public synchronized long deleteBySubject(String subject) {
        check();
        long before = counts.size();
        counts.keySet().removeIf(k -> k.subject().equals(subject));
        return before - counts.size();
    }

    @Override
    public synchronized List<ToolQuotaUsage> findBySubject(String subject, int max) {
        check();
        List<ToolQuotaUsage> found = new ArrayList<>();
        counts.forEach((k, v) -> {
            if (k.subject().equals(subject) && found.size() < max) {
                found.add(new ToolQuotaUsage(k.agentId(), k.counter(), k.windowStart(), v));
            }
        });
        return found;
    }

    @Override
    public synchronized int deleteExpired(Instant now) {
        check();
        return 0;
    }

    /** Sum over every counter. */
    public synchronized int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The count of one counter, without touching the round-trip tally. */
    public synchronized int peek(ToolQuotaKey key) {
        return counts.getOrDefault(key, 0);
    }
}
