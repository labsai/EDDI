/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RestExportService — an export that cannot be complete fails")
class RestExportServiceIncompleteTest {

    @Test
    @DisplayName("the failure is a 500 with a stable message that names what is missing and no exception text (E9)")
    void incompleteExportIsAStableServerError() {
        WebApplicationException failure = RestExportService.exportIncomplete("the prompt snippets the agent uses");

        assertEquals(500, failure.getResponse().getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) failure.getResponse().getEntity();
        assertTrue(body.get("error").startsWith("Export failed: the archive could not include the prompt snippets the agent uses"),
                body.get("error"));
        assertTrue(body.get("error").contains("no archive was produced"));
        assertFalse(body.get("error").contains("Exception"));
        assertEquals(body.get("error"), failure.getMessage());
    }
}
