/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.KvKeys;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Token buckets shared by every node (cluster mode): the same refill-and-take
 * arithmetic as {@code ToolRateLimiter.RateLimitBucket}, kept in a KV entry
 * {@code {tokens, lastRefillMs, limit}} and advanced by compare-and-set.
 * <p>
 * A per-conversation bucket is uncontended in practice (the conversation's turn
 * holds its lease); the global bucket is contended, so a take retries a few
 * times with jitter and then <em>denies</em> — a limit is never exceeded
 * because of contention.
 */
class KvRateLimitBackend {

    enum Decision {
        ALLOWED, DENIED, UNAVAILABLE
    }

    private static final int MAX_ATTEMPTS = 5;

    private final ISharedKv kv;
    private final MeterRegistry meterRegistry;
    private final LongSupplier clock;

    KvRateLimitBackend(ISharedKv kv, MeterRegistry meterRegistry, LongSupplier clock) {
        this.kv = kv;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    static String key(String scope, String toolName) {
        return "b." + KvKeys.hashed(scope + "|" + toolName);
    }

    private record State(double tokens, long lastRefillMs, int limit) {
        byte[] encode() {
            return (tokens + "|" + lastRefillMs + "|" + limit).getBytes(StandardCharsets.UTF_8);
        }

        static State decode(byte[] bytes) {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|");
            return new State(Double.parseDouble(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
        }

        State refilled(long now, int newLimit) {
            int sanitised = Math.max(0, newLimit);
            double consumed = limit - tokens;
            double base = sanitised == limit ? tokens : Math.max(0.0, Math.min(sanitised, sanitised - consumed));
            long elapsed = Math.max(0, now - lastRefillMs);
            double refilled = Math.min(sanitised, base + (double) elapsed * sanitised / ToolRateLimiter.WINDOW_MS);
            return new State(refilled, now, sanitised);
        }
    }

    Decision take(String key, int limit) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                long now = clock.getAsLong();
                Optional<ISharedKv.Versioned> current = kv.get(key);
                State state = current.map(v -> State.decode(v.value()).refilled(now, limit))
                        .orElse(new State(Math.max(0, limit), now, Math.max(0, limit)));
                if (state.tokens() < 1.0) {
                    return Decision.DENIED;
                }
                State taken = new State(state.tokens() - 1.0, state.lastRefillMs(), state.limit());
                OptionalLong written = current.isPresent()
                        ? kv.update(key, taken.encode(), current.get().revision())
                        : kv.create(key, taken.encode());
                if (written.isPresent()) {
                    return Decision.ALLOWED;
                }
                meterRegistry.counter("eddi.cluster.ratelimit.cas.retries").increment();
                sleepJitter(attempt);
            }
            return Decision.DENIED; // contention never loosens a limit
        } catch (ClusterUnavailableException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return Decision.UNAVAILABLE;
        }
    }

    /** Gives back a token taken by {@link #take} (best effort). */
    void giveBack(String key) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Optional<ISharedKv.Versioned> current = kv.get(key);
                if (current.isEmpty()) {
                    return;
                }
                State state = State.decode(current.get().value());
                State returned = new State(Math.min(state.limit(), state.tokens() + 1.0), state.lastRefillMs(), state.limit());
                if (kv.update(key, returned.encode(), current.get().revision()).isPresent()) {
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // the token comes back with the refill anyway
        }
    }

    private static void sleepJitter(int attempt) {
        try {
            Thread.sleep(1 + ThreadLocalRandom.current().nextInt(2 << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
