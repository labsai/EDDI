/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedCallScopeTest {

    private static final String AGENT = "68f1c0ffee0000000000a9e7";
    private static final String OWN_WRITE = "http://x/agentstore/agents/" + AGENT + "/updateResourceUri";

    @Test
    void checksOnlyInsideAnApprovedExecution() {
        assertDoesNotThrow(() -> ApprovedCallScope.checkOutgoing("PUT", OWN_WRITE, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovedCallScope.run(AGENT, () -> {
            ApprovedCallScope.checkOutgoing("PUT", OWN_WRITE, null);
            return null;
        }));
        assertFalse(ApprovedCallScope.isActive(), "the binding is removed afterwards");
    }

    @Test
    void followsTheCallOntoTheToolExecutorThread() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = ApprovedCallScope.run(AGENT, () -> executor.submit(ApprovedCallScope.propagate(() -> {
                ApprovedCallScope.checkOutgoing("PUT", OWN_WRITE, null);
                return "sent";
            })));
            var e = assertThrows(ExecutionException.class, future::get);
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().startsWith("NOT_EXECUTED"));
        } finally {
            executor.shutdownNow();
        }
    }
}
