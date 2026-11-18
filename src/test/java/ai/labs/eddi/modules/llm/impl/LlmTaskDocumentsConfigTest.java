/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deploy time never refuses a {@code documents} value: a stored configuration
 * that carries a key not available in this version (written before a key was
 * withdrawn, saved by a newer version, or written straight to the database)
 * loads, resolves to off, and logs one WARN naming the task and the key.
 */
@DisplayName("LlmTask.configure — documents block")
class LlmTaskDocumentsConfigTest {

    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger julLogger = Logger.getLogger(LlmTask.class.getName());
    private Level previousLevel;

    // logging.properties turns the ai.labs.eddi namespace OFF for plain unit tests,
    // so the logger is opened explicitly.
    @BeforeEach
    void attach() {
        previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
    }

    @AfterEach
    void detach() {
        julLogger.removeHandler(handler);
        julLogger.setLevel(previousLevel);
    }

    private static String message(LogRecord record) {
        Object[] params = record.getParameters();
        String msg = record.getMessage();
        return params == null || params.length == 0 ? msg : String.format(msg.replace("{0}", "%s"), params);
    }

    @Test
    @DisplayName("a stored key that is not available deploys and logs exactly one WARN")
    void storedNonLiveKeyWarnsOnce() throws Exception {
        var config = new ObjectMapper().readValue("""
                {"tasks":[{"actions":["help"],"id":"answer","type":"openai",
                  "documents":{"sharing":{"delegation":true}}}]}
                """, LlmConfiguration.class);

        assertDoesNotThrow(() -> LlmTask.warnOnDocumentsConfig(config, null));

        assertEquals(1, warnings.size(), warnings.stream().map(LlmTaskDocumentsConfigTest::message).toList().toString());
        String text = message(warnings.get(0));
        assertTrue(text.contains("answer") && text.contains("documents.sharing.delegation is not available"), text);
    }

    @Test
    @DisplayName("no documents block, or an empty one: no WARN")
    void absentOrEmptyIsSilent() throws Exception {
        var mapper = new ObjectMapper();
        LlmTask.warnOnDocumentsConfig(mapper.readValue("{\"tasks\":[{\"actions\":[\"a\"],\"type\":\"openai\"}]}", LlmConfiguration.class), null);
        LlmTask.warnOnDocumentsConfig(
                mapper.readValue("{\"tasks\":[{\"actions\":[\"a\"],\"type\":\"openai\",\"documents\":{}}]}", LlmConfiguration.class), null);
        LlmTask.warnOnDocumentsConfig(null, null);
        LlmTask.warnOnDocumentsConfig(new LlmConfiguration(null), null);
        assertTrue(warnings.isEmpty(), warnings.stream().map(LlmTaskDocumentsConfigTest::message).toList().toString());
    }
}
