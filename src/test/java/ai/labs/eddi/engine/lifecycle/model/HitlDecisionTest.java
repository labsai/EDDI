/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HitlDecision")
class HitlDecisionTest {

    private HitlDecision decision;

    @BeforeEach
    void setUp() {
        decision = new HitlDecision();
    }

    @Nested
    @DisplayName("default constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("verdict is null by default")
        void verdictIsNull() {
            assertNull(decision.getVerdict());
        }

        @Test
        @DisplayName("note is null by default")
        void noteIsNull() {
            assertNull(decision.getNote());
        }

        @Test
        @DisplayName("decidedBy is null by default")
        void decidedByIsNull() {
            assertNull(decision.getDecidedBy());
        }
    }

    @Nested
    @DisplayName("setters and getters")
    class SettersAndGetters {

        @Test
        @DisplayName("setVerdict/getVerdict round-trips APPROVED")
        void verdictApproved() {
            decision.setVerdict(HitlVerdict.APPROVED);
            assertEquals(HitlVerdict.APPROVED, decision.getVerdict());
        }

        @Test
        @DisplayName("setVerdict/getVerdict round-trips REJECTED")
        void verdictRejected() {
            decision.setVerdict(HitlVerdict.REJECTED);
            assertEquals(HitlVerdict.REJECTED, decision.getVerdict());
        }

        @Test
        @DisplayName("setNote/getNote round-trips")
        void noteRoundTrip() {
            decision.setNote("Looks good");
            assertEquals("Looks good", decision.getNote());
        }

        @Test
        @DisplayName("setDecidedBy/getDecidedBy round-trips")
        void decidedByRoundTrip() {
            decision.setDecidedBy("admin-user");
            assertEquals("admin-user", decision.getDecidedBy());
        }

        @Test
        @DisplayName("setters accept null values")
        void settersAcceptNull() {
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setNote("note");
            decision.setDecidedBy("user");

            decision.setVerdict(null);
            decision.setNote(null);
            decision.setDecidedBy(null);

            assertNull(decision.getVerdict());
            assertNull(decision.getNote());
            assertNull(decision.getDecidedBy());
        }
    }

    @Nested
    @DisplayName("HitlVerdict enum")
    class HitlVerdictEnum {

        @Test
        @DisplayName("APPROVED exists")
        void approvedExists() {
            assertNotNull(HitlVerdict.APPROVED);
        }

        @Test
        @DisplayName("REJECTED exists")
        void rejectedExists() {
            assertNotNull(HitlVerdict.REJECTED);
        }

        @Test
        @DisplayName("values() returns exactly 2 entries")
        void valuesReturnsTwoEntries() {
            assertEquals(2, HitlVerdict.values().length);
        }

        @Test
        @DisplayName("valueOf('APPROVED') returns APPROVED")
        void valueOfApproved() {
            assertEquals(HitlVerdict.APPROVED, HitlVerdict.valueOf("APPROVED"));
        }

        @Test
        @DisplayName("valueOf('REJECTED') returns REJECTED")
        void valueOfRejected() {
            assertEquals(HitlVerdict.REJECTED, HitlVerdict.valueOf("REJECTED"));
        }

        @Test
        @DisplayName("valueOf with invalid name throws IllegalArgumentException")
        void valueOfInvalidThrows() {
            assertThrows(IllegalArgumentException.class, () -> HitlVerdict.valueOf("PENDING"));
        }
    }
}
