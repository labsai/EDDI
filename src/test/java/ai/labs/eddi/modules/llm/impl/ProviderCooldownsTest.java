/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared cooldown seen from the embedding throttle, and the key it is
 * shared under.
 */
class ProviderCooldownsTest {

    private Instant now = Instant.parse("2026-11-23T10:00:00Z");
    private final List<Duration> sleeps = new ArrayList<>();

    /** A store in memory, as either database behaves. */
    private static final class MapStore implements IProviderCooldownStore {
        final Map<String, Cooldown> rows = new HashMap<>();

        @Override
        public Optional<Cooldown> read(String throttleKey) {
            return Optional.ofNullable(rows.get(throttleKey));
        }

        @Override
        public void extend(String throttleKey, Instant until, boolean daily) {
            rows.merge(throttleKey, new Cooldown(throttleKey, until, daily),
                    (old, fresh) -> fresh.until().isAfter(old.until()) ? fresh : old);
        }
    }

    /** Counts calls; throttles while told to. */
    private final class Provider implements EmbeddingModel {
        int calls;
        boolean throttling;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls++;
            if (throttling) {
                throw new RateLimitException(new HttpException(429, "retry in 120s"));
            }
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{0.1f})).toList());
        }
    }

    private ThrottledEmbeddingModel model(Provider provider, ProviderCooldowns cooldowns, Instant deadline) {
        ThrottledEmbeddingModel throttled = ThrottledEmbeddingModel.of(provider, "gemini", 0, false, null,
                duration -> {
                    sleeps.add(duration);
                    now = now.plus(duration);
                }, () -> now).sharingCooldown(cooldowns, "gemini|default|m|key:1");
        return (ThrottledEmbeddingModel) ThrottledEmbeddingModel.boundTo(throttled, deadline);
    }

    @Test
    @DisplayName("a throttle one run meets stops another run's calls before they reach the provider")
    void aThrottleIsShared() {
        MapStore store = new MapStore();
        ProviderCooldowns cooldowns = new ProviderCooldowns(store, () -> now);
        Provider first = new Provider();
        first.throttling = true;
        assertThrows(ProviderThrottledException.class,
                () -> model(first, cooldowns, now.plusSeconds(30)).embedAll(List.of(TextSegment.from("x"))));
        assertEquals(1, store.rows.size(), "the throttle was recorded");

        // Another run, another node: its own cache, the same rows.
        Provider second = new Provider();
        ThrottledEmbeddingModel other = model(second, new ProviderCooldowns(store, () -> now), now.plusSeconds(30));
        assertTrue(other.coolingDown().isPresent(), "the drain sees it before claiming");
        assertThrows(ProviderThrottledException.class, () -> other.embedAll(List.of(TextSegment.from("y"))));
        assertEquals(0, second.calls, "no call is spent on a quota already known to refuse");
    }

    @Test
    @DisplayName("a cooldown that ends within the deadline is waited out, then the call goes")
    void aShortCooldownIsWaitedOut() {
        MapStore store = new MapStore();
        store.extend("gemini|default|m|key:1", now.plusSeconds(20), false);
        Provider provider = new Provider();
        ThrottledEmbeddingModel model = model(provider, new ProviderCooldowns(store, () -> now), now.plusSeconds(60));
        assertFalse(model.coolingDown().isPresent(), "short enough to wait, so the drain claims");

        model.embedAll(List.of(TextSegment.from("x")));
        assertEquals(1, provider.calls);
        assertEquals(List.of(Duration.ofSeconds(20)), sleeps);
    }

    @Test
    @DisplayName("the throttle key names the quota without the credential")
    void theKeyHidesTheCredential() {
        String key = ProviderThrottleKey.of("gemini", Map.of("apiKey", "secret-key-value", "model", "gemini-embedding-2"));
        assertFalse(key.contains("secret-key-value"));
        assertTrue(key.startsWith("gemini|default|gemini-embedding-2|key:"));
        assertNotEquals(key, ProviderThrottleKey.of("gemini", Map.of("apiKey", "another", "model", "gemini-embedding-2")));
        assertEquals("vertex|europe-west4|text-embedding-005|project:p1",
                ProviderThrottleKey.of("vertex", Map.of("project", "p1", "location", "europe-west4",
                        "modelName", "text-embedding-005")));
        assertTrue(ProviderThrottleKey.of("openai", Map.of("baseUrl", "https://api.example.com/v1", "apiKey", "k"))
                .startsWith("openai|api.example.com|"));
    }
}
