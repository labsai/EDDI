/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.agents;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The library logged under {@code AgentFactory}'s category, so an operator
 * raising or silencing that category changed this class's output instead, and a
 * log line pointed at the wrong class.
 */
@DisplayName("AgentStoreClientLibrary — logger category")
class AgentStoreClientLibraryLoggerTest {

    @Test
    @DisplayName("logs under its own class name")
    void logsUnderItsOwnCategory() throws Exception {
        var field = AgentStoreClientLibrary.class.getDeclaredField("LOGGER");
        field.setAccessible(true);

        assertEquals(AgentStoreClientLibrary.class.getName(), ((Logger) field.get(null)).getName());
    }
}
