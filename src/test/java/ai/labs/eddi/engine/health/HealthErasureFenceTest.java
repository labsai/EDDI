/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.cluster.KvKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthErasureFence — holds back a user's health rows while their erasure runs")
class HealthErasureFenceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private Instant clockNow = NOW;

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return clockNow;
        }
    };

    @Test
    @DisplayName("no fence, no cover; a local stop covers the user and nobody else")
    void localStop() {
        HealthErasureFence fence = new HealthErasureFence(clock);
        assertFalse(fence.covers("user-1"));

        assertEquals(0, fence.stopInFlightWork("user-1"));

        assertTrue(fence.covers("user-1"));
        assertFalse(fence.covers("user-2"));
        assertFalse(fence.covers(null));
    }

    @Test
    @DisplayName("the cluster stop arrives as the hash and covers the same user")
    void remoteStopByHash() {
        HealthErasureFence fence = new HealthErasureFence(clock);
        fence.stopInFlightWorkByHash(KvKeys.sha256("user-1"));
        assertTrue(fence.covers("user-1"));
    }

    @Test
    @DisplayName("every capture is covered while the fence lasts — also one taken after the erasure started")
    void coversLaterCaptures() {
        HealthErasureFence fence = new HealthErasureFence(clock);
        fence.stopInFlightWork("user-1");
        clockNow = NOW.plus(HealthErasureFence.FENCE_TTL).minusSeconds(1);
        assertTrue(fence.covers("user-1"));
    }

    @Test
    @DisplayName("a fence expires after its TTL, and expired fences are swept on the next stop")
    void expires() {
        HealthErasureFence fence = new HealthErasureFence(clock);
        fence.stopInFlightWork("user-1");
        clockNow = NOW.plus(HealthErasureFence.FENCE_TTL);
        assertFalse(fence.covers("user-1"));
        fence.stopInFlightWork("user-2");
        assertFalse(fence.covers("user-1"));
        assertTrue(fence.covers("user-2"));
    }

    @Test
    @DisplayName("blank ids are ignored, and the step has a name")
    void blank() {
        HealthErasureFence fence = new HealthErasureFence(clock);
        assertEquals(0, fence.stopInFlightWork(" "));
        assertEquals(0, fence.stopInFlightWork(null));
        assertEquals(0, fence.stopInFlightWorkByHash(""));
        assertFalse(fence.covers(""));
        assertEquals("agentHealthErasureFence", fence.erasureStepName());
    }
}
