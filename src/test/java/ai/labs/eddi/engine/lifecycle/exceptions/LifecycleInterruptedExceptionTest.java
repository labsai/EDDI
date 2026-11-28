/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("LifecycleInterruptedException")
class LifecycleInterruptedExceptionTest {

    @Test
    @DisplayName("the (message, cause) constructor keeps the cause")
    void keepsTheCause() {
        var cause = new InterruptedException("interrupted while backing off");

        var ex = new LifecycleException.LifecycleInterruptedException("retry interrupted", cause);

        assertEquals("retry interrupted", ex.getMessage());
        assertSame(cause, ex.getCause(), "the interrupt that ended the retry must stay on the stack trace");
    }

    @Test
    @DisplayName("the message-only constructor has no cause")
    void messageOnly() {
        assertNull(new LifecycleException.LifecycleInterruptedException("stopped").getCause());
    }
}
