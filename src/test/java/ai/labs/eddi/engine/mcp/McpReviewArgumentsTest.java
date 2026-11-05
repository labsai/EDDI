/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpReviewArgumentsTest {

    @Test
    void aSingleDecisionObjectIsAccepted() throws Exception {
        var decisions = McpReviewArguments.taskDecisions("{\"t1\":{\"verdict\":\"APPROVED\"}}");
        assertEquals(1, decisions.size());
    }

    @Test
    void surplusJsonAfterTheObjectIsRejectedInsteadOfDropped() {
        assertThrows(McpReviewArguments.BadArgument.class,
                () -> McpReviewArguments.taskDecisions("{\"t1\":{\"verdict\":\"APPROVED\"}}{\"t2\":{\"verdict\":\"REJECTED\"}}"));
    }
}
