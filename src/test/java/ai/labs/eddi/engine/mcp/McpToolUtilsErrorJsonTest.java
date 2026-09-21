/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolUtilsErrorJsonTest {

    @Test
    void errorJson_withCodeAndDetails_producesStructuredJson() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("currentState", "IN_PROGRESS");
        String json = McpToolUtils.errorJson("not resumable", "WRONG_STATE", details);
        assertTrue(json.contains("\"error\":\"not resumable\""), json);
        assertTrue(json.contains("\"errorCode\":\"WRONG_STATE\""), json);
        assertTrue(json.contains("\"currentState\":\"IN_PROGRESS\""), json);
    }

    @Test
    void errorJson_withNullDetails_omitsDetailsObject() {
        String json = McpToolUtils.errorJson("bad", "BAD_REQUEST", null);
        assertTrue(json.contains("\"errorCode\":\"BAD_REQUEST\""), json);
        assertFalse(json.contains("\"details\""), json);
    }

    @Test
    void errorJson_withBlankErrorCode_omitsErrorCode() {
        String json = McpToolUtils.errorJson("oops", "", null);
        assertTrue(json.contains("\"error\":\"oops\""), json);
        assertFalse(json.contains("\"errorCode\""), json);
    }

    @Test
    void errorJson_escapesQuotesInMessage() {
        String json = McpToolUtils.errorJson("he said \"hi\"", "X", null);
        assertTrue(json.contains("\\\"hi\\\""), json);
    }
}
