/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DiscussionControlToken")
class DiscussionControlTokenTest {

    private DiscussionControlToken token;

    @BeforeEach
    void setUp() {
        token = new DiscussionControlToken();
    }

    @Nested
    @DisplayName("initial state")
    class InitialState {

        @Test
        @DisplayName("initial signal is CONTINUE")
        void initialSignalIsContinue() {
            assertEquals(ControlSignal.CONTINUE, token.getSignal());
        }

        @Test
        @DisplayName("isCancelled is false initially")
        void isCancelledFalseInitially() {
            assertFalse(token.isCancelled());
        }

        @Test
        @DisplayName("isPaused is false initially")
        void isPausedFalseInitially() {
            assertFalse(token.isPaused());
        }

        @Test
        @DisplayName("shouldStop is false initially")
        void shouldStopFalseInitially() {
            assertFalse(token.shouldStop());
        }
    }

    @Nested
    @DisplayName("setSignal")
    class SetSignal {

        @Test
        @DisplayName("setSignal changes the signal")
        void setSignalChangesSignal() {
            token.setSignal(ControlSignal.PAUSE);
            assertEquals(ControlSignal.PAUSE, token.getSignal());
        }

        @Test
        @DisplayName("multiple setSignal calls are idempotent — last one wins")
        void multipleSetSignalCalls() {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            token.setSignal(ControlSignal.PAUSE);
            token.setSignal(ControlSignal.CONTINUE);
            assertEquals(ControlSignal.CONTINUE, token.getSignal());
        }
    }

    @Nested
    @DisplayName("isCancelled")
    class IsCancelled {

        @Test
        @DisplayName("true for CANCEL_GRACEFUL")
        void trueForCancelGraceful() {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            assertTrue(token.isCancelled());
        }

        @Test
        @DisplayName("true for CANCEL_IMMEDIATE")
        void trueForCancelImmediate() {
            token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
            assertTrue(token.isCancelled());
        }

        @Test
        @DisplayName("false for CONTINUE")
        void falseForContinue() {
            token.setSignal(ControlSignal.CONTINUE);
            assertFalse(token.isCancelled());
        }

        @Test
        @DisplayName("false for PAUSE")
        void falseForPause() {
            token.setSignal(ControlSignal.PAUSE);
            assertFalse(token.isCancelled());
        }
    }

    @Nested
    @DisplayName("isPaused")
    class IsPaused {

        @Test
        @DisplayName("true only for PAUSE")
        void trueForPause() {
            token.setSignal(ControlSignal.PAUSE);
            assertTrue(token.isPaused());
        }

        @Test
        @DisplayName("false for CONTINUE")
        void falseForContinue() {
            token.setSignal(ControlSignal.CONTINUE);
            assertFalse(token.isPaused());
        }

        @Test
        @DisplayName("false for CANCEL_GRACEFUL")
        void falseForCancelGraceful() {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            assertFalse(token.isPaused());
        }

        @Test
        @DisplayName("false for CANCEL_IMMEDIATE")
        void falseForCancelImmediate() {
            token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
            assertFalse(token.isPaused());
        }
    }

    @Nested
    @DisplayName("shouldStop")
    class ShouldStop {

        @Test
        @DisplayName("true for CANCEL_GRACEFUL")
        void trueForCancelGraceful() {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            assertTrue(token.shouldStop());
        }

        @Test
        @DisplayName("true for CANCEL_IMMEDIATE")
        void trueForCancelImmediate() {
            token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
            assertTrue(token.shouldStop());
        }

        @Test
        @DisplayName("true for PAUSE")
        void trueForPause() {
            token.setSignal(ControlSignal.PAUSE);
            assertTrue(token.shouldStop());
        }

        @Test
        @DisplayName("false for CONTINUE")
        void falseForContinue() {
            token.setSignal(ControlSignal.CONTINUE);
            assertFalse(token.shouldStop());
        }
    }

    @Nested
    @DisplayName("activeFuture management")
    class ActiveFuture {

        @Test
        @DisplayName("cancelActiveFuture with no future set does not throw")
        void cancelWithNoFutureIsNoOp() {
            assertDoesNotThrow(() -> token.cancelActiveFuture());
        }

        @Test
        @DisplayName("setActiveFuture + cancelActiveFuture cancels the future")
        void cancelActiveFutureCancelsFuture() {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> future = mock(CompletableFuture.class);

            token.setActiveFuture(future);
            token.cancelActiveFuture();

            verify(future).cancel(true);
        }

        @Test
        @DisplayName("cancelActiveFuture calls cancel with mayInterruptIfRunning=true")
        void cancelWithInterrupt() {
            CompletableFuture<String> future = new CompletableFuture<>();
            token.setActiveFuture(future);

            token.cancelActiveFuture();

            assertTrue(future.isCancelled());
        }
    }
}
