/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HitlTimeoutPolicy")
class HitlTimeoutPolicyTest {

    @Nested
    @DisplayName("enum values")
    class EnumValues {

        @Test
        @DisplayName("AUTO_REJECT exists")
        void autoRejectExists() {
            assertNotNull(HitlTimeoutPolicy.AUTO_REJECT);
        }

        @Test
        @DisplayName("AUTO_APPROVE exists")
        void autoApproveExists() {
            assertNotNull(HitlTimeoutPolicy.AUTO_APPROVE);
        }

        @Test
        @DisplayName("ABORT exists")
        void abortExists() {
            assertNotNull(HitlTimeoutPolicy.ABORT);
        }

        @Test
        @DisplayName("WAIT_INDEFINITELY exists")
        void waitIndefinitelyExists() {
            assertNotNull(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        }

        @Test
        @DisplayName("values() returns exactly 4 entries")
        void valuesReturnsFour() {
            assertEquals(4, HitlTimeoutPolicy.values().length);
        }
    }

    @Nested
    @DisplayName("valueOf")
    class ValueOf {

        @Test
        @DisplayName("valueOf('AUTO_REJECT') returns AUTO_REJECT")
        void valueOfAutoReject() {
            assertEquals(HitlTimeoutPolicy.AUTO_REJECT, HitlTimeoutPolicy.valueOf("AUTO_REJECT"));
        }

        @Test
        @DisplayName("valueOf('AUTO_APPROVE') returns AUTO_APPROVE")
        void valueOfAutoApprove() {
            assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, HitlTimeoutPolicy.valueOf("AUTO_APPROVE"));
        }

        @Test
        @DisplayName("valueOf('ABORT') returns ABORT")
        void valueOfAbort() {
            assertEquals(HitlTimeoutPolicy.ABORT, HitlTimeoutPolicy.valueOf("ABORT"));
        }

        @Test
        @DisplayName("valueOf('WAIT_INDEFINITELY') returns WAIT_INDEFINITELY")
        void valueOfWaitIndefinitely() {
            assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, HitlTimeoutPolicy.valueOf("WAIT_INDEFINITELY"));
        }

        @Test
        @DisplayName("valueOf with invalid name throws IllegalArgumentException")
        void valueOfInvalidThrows() {
            assertThrows(IllegalArgumentException.class, () -> HitlTimeoutPolicy.valueOf("INVALID"));
        }
    }
}
