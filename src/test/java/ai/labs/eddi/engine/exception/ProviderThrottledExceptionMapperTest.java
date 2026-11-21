/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.modules.llm.impl.ProviderThrottledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderThrottledExceptionMapperTest {

    private static final String QUOTA_ID = "EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier";

    private final ProviderThrottledExceptionMapper mapper = new ProviderThrottledExceptionMapper();

    @Test
    @DisplayName("a rate limit is a 429 whose Retry-After is the provider's delay, rounded up")
    void rateLimit() {
        var response = mapper.toResponse(new ProviderThrottledException("gemini", Duration.ofMillis(25_300), false,
                QUOTA_ID, null));

        assertEquals(429, response.getStatus());
        assertEquals("26", response.getHeaderString("Retry-After"));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertEquals("provider_throttled", body.get("error"));
        assertEquals(26L, body.get("retryAfterSeconds"));
        assertFalse(String.valueOf(body.get("message")).contains(QUOTA_ID), "the provider's text stays in the log");
    }

    @Test
    @DisplayName("with no delay named, Retry-After is the 30 s fallback for a rate limit")
    void rateLimitWithoutDelay() {
        var response = mapper.toResponse(new ProviderThrottledException("openai", null, false, null, null));

        assertEquals("30", response.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("a spent daily quota with no time named says so, and asks for an hour")
    void daily() {
        var response = mapper.toResponse(new ProviderThrottledException("gemini", null, true, null, null));

        assertEquals(429, response.getStatus());
        assertEquals(String.valueOf(ProviderThrottledExceptionMapper.DAILY_FALLBACK_SECONDS),
                response.getHeaderString("Retry-After"));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertTrue(String.valueOf(body.get("message")).contains("daily"));
    }

    @Test
    @DisplayName("a sub-second delay still asks for at least one second")
    void subSecond() {
        var response = mapper.toResponse(new ProviderThrottledException("openai", Duration.ofMillis(20), false,
                null, null));

        assertEquals("1", response.getHeaderString("Retry-After"));
    }
}
