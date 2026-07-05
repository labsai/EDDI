/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ControlSignal")
class ControlSignalTest {

    @Nested
    @DisplayName("enum values")
    class EnumValues {

        @Test
        @DisplayName("CONTINUE exists")
        void continueExists() {
            assertNotNull(ControlSignal.CONTINUE);
        }

        @Test
        @DisplayName("CANCEL_GRACEFUL exists")
        void cancelGracefulExists() {
            assertNotNull(ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("CANCEL_IMMEDIATE exists")
        void cancelImmediateExists() {
            assertNotNull(ControlSignal.CANCEL_IMMEDIATE);
        }

        @Test
        @DisplayName("values() contains the core entries — PAUSE was removed (HITL pauses are committed by the execution loop, never signalled)")
        void valuesReturnsAllThree() {
            // PAUSE removal is proven by valueOf("PAUSE") throwing (asserted separately);
            // this only guards the lower bound so a future signal does not fail it.
            assertTrue(ControlSignal.values().length >= 3);
        }
    }

    @Nested
    @DisplayName("valueOf")
    class ValueOf {

        @Test
        @DisplayName("valueOf('CONTINUE') returns CONTINUE")
        void valueOfContinue() {
            assertEquals(ControlSignal.CONTINUE, ControlSignal.valueOf("CONTINUE"));
        }

        @Test
        @DisplayName("valueOf('CANCEL_GRACEFUL') returns CANCEL_GRACEFUL")
        void valueOfCancelGraceful() {
            assertEquals(ControlSignal.CANCEL_GRACEFUL, ControlSignal.valueOf("CANCEL_GRACEFUL"));
        }

        @Test
        @DisplayName("valueOf('CANCEL_IMMEDIATE') returns CANCEL_IMMEDIATE")
        void valueOfCancelImmediate() {
            assertEquals(ControlSignal.CANCEL_IMMEDIATE, ControlSignal.valueOf("CANCEL_IMMEDIATE"));
        }

        @Test
        @DisplayName("valueOf('PAUSE') throws — the dead signal must not resurface")
        void valueOfPauseThrows() {
            assertThrows(IllegalArgumentException.class, () -> ControlSignal.valueOf("PAUSE"));
        }

        @Test
        @DisplayName("valueOf with invalid name throws IllegalArgumentException")
        void valueOfInvalidThrows() {
            assertThrows(IllegalArgumentException.class, () -> ControlSignal.valueOf("INVALID"));
        }
    }
}
