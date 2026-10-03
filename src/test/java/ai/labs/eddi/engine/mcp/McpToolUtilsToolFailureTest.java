/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolUtils#toolFailure}: which failures an MCP client may read
 * verbatim. EDDI's own validation refusals are written for the caller; an
 * {@link IllegalArgumentException} raised inside a library is not, and can
 * quote server paths or class names.
 */
@DisplayName("McpToolUtils.toolFailure")
class McpToolUtilsToolFailureTest {

    private static final Logger LOG = Logger.getLogger(McpToolUtilsToolFailureTest.class);

    @Test
    @DisplayName("an IllegalArgumentException EDDI raised is described to the caller")
    void eddiValidationIsDescribed() {
        String result = McpToolUtils.toolFailure(LOG, "create_group",
                new IllegalArgumentException("members[1] repeats members[0] ('a')"));

        assertTrue(result.contains("members[1] repeats members[0]"), result);
        assertFalse(result.contains("INTERNAL_ERROR"), result);
    }

    @Test
    @DisplayName("a library's IllegalArgumentException (java.nio) is not echoed: it quotes a server path")
    void nioPathIsHidden() {
        IllegalArgumentException nio = assertThrows(IllegalArgumentException.class,
                () -> Path.of("C:/srv/eddi/secret-dir/" + (char) 0 + "x"));

        String result = McpToolUtils.toolFailure(LOG, "read_group", nio);

        assertFalse(result.contains("secret-dir"), result);
        assertTrue(result.contains("\"errorCode\":\"INTERNAL_ERROR\""), result);
    }

    @Test
    @DisplayName("a library's IllegalArgumentException (Jackson) is not echoed: it names model classes")
    void jacksonIsHidden() {
        IllegalArgumentException jackson = assertThrows(IllegalArgumentException.class,
                () -> new ObjectMapper().convertValue(Map.of("members", "not-a-list"), Holder.class));

        String result = McpToolUtils.toolFailure(LOG, "update_group", jackson);

        assertFalse(result.contains("Holder"), result);
        assertFalse(result.contains("McpToolUtilsToolFailureTest"), result);
        assertTrue(result.contains("\"errorCode\":\"INTERNAL_ERROR\""), result);
    }

    @Test
    @DisplayName("a NumberFormatException from the JDK is not echoed either")
    void numberFormatIsHidden() {
        NumberFormatException nfe = null;
        try {
            Integer.parseInt("hunter2");
        } catch (NumberFormatException expected) {
            // Caught on purpose: the JDK's own exception is the fixture.
            nfe = expected;
        }
        assertNotNull(nfe, "fixture: parseInt must refuse the input");

        String result = McpToolUtils.toolFailure(LOG, "list_groups", nfe);

        assertFalse(result.contains("hunter2"), result);
        assertTrue(result.contains("reference"), result);
    }

    /** Target type for the Jackson case. */
    static final class Holder {
        public List<String> members;
    }
}
