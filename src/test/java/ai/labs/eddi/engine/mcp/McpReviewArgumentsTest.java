/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class McpReviewArgumentsTest {

    private final IJsonSerialization json = mock(IJsonSerialization.class);

    @Test
    void aSingleDecisionObjectIsAccepted() throws Exception {
        var decisions = McpReviewArguments.taskDecisions(json, "{\"t1\":{\"verdict\":\"APPROVED\"}}");
        assertEquals(1, decisions.size());
    }

    @Test
    void surplusJsonAfterTheObjectIsRejectedInsteadOfDropped() {
        assertThrows(McpReviewArguments.BadArgument.class,
                () -> McpReviewArguments.taskDecisions(json, "{\"t1\":{\"verdict\":\"APPROVED\"}}{\"t2\":{\"verdict\":\"REJECTED\"}}"));
    }
}
