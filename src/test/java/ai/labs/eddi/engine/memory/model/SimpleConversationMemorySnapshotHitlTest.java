/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SimpleConversationMemorySnapshot — HITL field")
class SimpleConversationMemorySnapshotHitlTest {

    private SimpleConversationMemorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new SimpleConversationMemorySnapshot();
    }

    @Nested
    @DisplayName("hitlPausedAt default value")
    class DefaultValue {

        @Test
        @DisplayName("hitlPausedAt defaults to null")
        void hitlPausedAtDefault() {
            assertNull(snapshot.getHitlPausedAt());
        }
    }

    @Nested
    @DisplayName("hitlPausedAt getter/setter round-trip")
    class GetterSetterRoundTrip {

        @Test
        @DisplayName("set and get returns the same Instant")
        void roundTrip() {
            Instant now = Instant.now();
            snapshot.setHitlPausedAt(now);
            assertEquals(now, snapshot.getHitlPausedAt());
        }

        @Test
        @DisplayName("set to null after setting a value")
        void setToNull() {
            snapshot.setHitlPausedAt(Instant.now());
            snapshot.setHitlPausedAt(null);
            assertNull(snapshot.getHitlPausedAt());
        }

        @Test
        @DisplayName("set to Instant.EPOCH")
        void setToEpoch() {
            snapshot.setHitlPausedAt(Instant.EPOCH);
            assertEquals(Instant.EPOCH, snapshot.getHitlPausedAt());
        }
    }
}
