/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.documents.DocumentBounds;
import ai.labs.eddi.engine.documents.DocumentSettings;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deploy time never refuses a {@code documents} value: a stored configuration
 * that carries a key not available in this version (saved by a newer version,
 * or written straight to the database) loads, resolves to off, and logs one
 * WARN naming the task and the key. The assertions read the lines
 * {@code warnOnDocumentsConfig} returns, which are exactly the lines it logs:
 * capturing the log itself is not reliable in a shared test JVM.
 */
@DisplayName("LlmTask.configure — documents block")
class LlmTaskDocumentsConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("a stored key that is not available deploys and logs exactly one WARN")
    void storedNonLiveKeyWarnsOnce() throws Exception {
        var config = MAPPER.readValue("""
                {"tasks":[{"actions":["help"],"id":"answer","type":"openai",
                  "documents":{"sharing":{"delegation":true}}}]}
                """, LlmConfiguration.class);

        List<String> logged = assertDoesNotThrow(() -> LlmTask.warnOnDocumentsConfig(config, null));

        assertEquals(1, logged.size(), logged.toString());
        String text = logged.get(0);
        assertTrue(text.contains("'answer'") && text.contains("documents.sharing.delegation is not available"), text);
    }

    @Test
    @DisplayName("no documents block, or an empty one: no WARN")
    void absentOrEmptyIsSilent() throws Exception {
        assertTrue(LlmTask.warnOnDocumentsConfig(MAPPER.readValue("{\"tasks\":[{\"actions\":[\"a\"],\"type\":\"openai\"}]}", LlmConfiguration.class),
                null).isEmpty());
        assertTrue(LlmTask.warnOnDocumentsConfig(
                MAPPER.readValue("{\"tasks\":[{\"actions\":[\"a\"],\"type\":\"openai\",\"documents\":{}}]}", LlmConfiguration.class), null)
                .isEmpty());
        assertTrue(LlmTask.warnOnDocumentsConfig(null, null).isEmpty());
        assertTrue(LlmTask.warnOnDocumentsConfig(new LlmConfiguration(null), null).isEmpty());
    }

    @Test
    @DisplayName("an operator default for an unavailable key is reported for a task without a block")
    void operatorDefaultReportedWithoutBlock() throws Exception {
        var config = MAPPER.readValue("{\"tasks\":[{\"actions\":[\"a\"],\"id\":\"plain\",\"type\":\"openai\"}]}", LlmConfiguration.class);
        var settings = DocumentSettings.of(Map.of("sharing.delegation", "true"), DocumentBounds.none());

        List<String> logged = LlmTask.warnOnDocumentsConfig(config, settings);

        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("'plain'") && logged.get(0).contains("eddi.documents.defaults.sharing.delegation"), logged.get(0));
    }
}
