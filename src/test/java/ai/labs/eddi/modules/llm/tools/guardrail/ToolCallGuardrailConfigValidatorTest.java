/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.guardrail;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolCallGuardrailConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolCallGuardrailConfigValidator")
public class ToolCallGuardrailConfigValidatorTest {

    private static final String PATH = "langchain.task[0].toolCallGuardrails";

    /** A valid entry, for tests that break one field of it. */
    public static ToolCallGuardrailConfig entry(String type, String action, List<String> tools, List<String> fields) {
        var config = new ToolCallGuardrailConfig();
        config.setType(type);
        config.setAction(action);
        config.setTools(tools);
        config.setFields(fields);
        return config;
    }

    private static ToolCallGuardrailConfig valid() {
        return entry("regex-filter", "block", List.of("mcp:add_issue_comment"), List.of("/body"));
    }

    private static String refused(ToolCallGuardrailConfig entry) {
        return assertThrows(IllegalArgumentException.class, () -> ToolCallGuardrailConfigValidator.validate(List.of(entry), PATH)).getMessage();
    }

    @Test
    @DisplayName("null, empty and the documented sample are valid")
    void valid_configs() {
        assertDoesNotThrow(() -> ToolCallGuardrailConfigValidator.validate(null, PATH));
        assertDoesNotThrow(() -> ToolCallGuardrailConfigValidator.validate(List.of(), PATH));
        var disclosure = entry("disclosure", "redact", List.of("mcp:add_issue_comment"), List.of("/body"));
        disclosure.setParameters(Map.of("text", "— AI-assisted reply"));
        var judge = entry("judge", "reprompt", List.of("mcp:add_issue_comment"), List.of("/body"));
        judge.setMaxRetries(1);
        assertDoesNotThrow(() -> ToolCallGuardrailConfigValidator.validate(
                List.of(valid(), entry("pii-redaction", "redact", List.of("mcp:*", "http.post:/issues/*"), null), judge, disclosure,
                        entry("expression", "warn", List.of("createInvoice"), List.of("", "/items/*/amount"))),
                PATH));
    }

    @Test
    @DisplayName("tools are required, and each pattern must be valid — a misspelled source gets a suggestion")
    void tools() {
        assertTrue(refused(entry("regex-filter", "block", null, null)).startsWith(PATH + "[0].tools is required"));
        String typo = refused(entry("regex-filter", "block", List.of("mpc:add_issue_comment"), null));
        assertTrue(typo.startsWith(PATH + "[0].tools[0]: "), typo);
        assertTrue(typo.contains("mcp"), typo);
        assertTrue(refused(entry("regex-filter", "block", List.of("bad pattern!"), null)).startsWith(PATH + "[0].tools[0]"));
        assertTrue(refused(entry("regex-filter", "block", Collections.nCopies(33, "mcp:x"), null)).contains("at most 32"));
    }

    @Test
    @DisplayName("fields must be JSON pointers, '*' only as a whole segment, at most 16")
    void fields() {
        assertTrue(refused(entry("regex-filter", "block", List.of("x"), List.of("body"))).startsWith(PATH + "[0].fields[0]: "));
        assertTrue(refused(entry("regex-filter", "block", List.of("x"), List.of("/items/x*"))).contains("whole segment"));
        assertTrue(refused(entry("regex-filter", "block", List.of("x"), List.of("/a~5"))).contains("~0"));
        assertTrue(refused(entry("regex-filter", "block", List.of("x"), Collections.nCopies(17, "/a"))).contains("at most 16"));
    }

    @Test
    @DisplayName("action is strict: a typo is an error, not a silent block")
    void action() {
        assertTrue(refused(entry("regex-filter", "blokc", List.of("x"), null)).contains("action must be one of block, redact, reprompt, warn"));
        assertDoesNotThrow(() -> ToolCallGuardrailConfigValidator.validate(List.of(entry("regex-filter", " Redact ", List.of("x"), null)), PATH));
    }

    @Test
    @DisplayName("disclosure needs redact")
    void disclosureNeedsRedact() {
        assertTrue(refused(entry("disclosure", "block", List.of("x"), null)).contains("use action 'redact'"));
    }

    @Test
    @DisplayName("type required; maxRetries 0..5; onExhausted only block; blockMessage bounded")
    void otherFields() {
        assertTrue(refused(entry(" ", "block", List.of("x"), null)).contains(".type is required"));
        var retries = valid();
        retries.setMaxRetries(6);
        assertTrue(refused(retries).contains("maxRetries must be between 0 and 5"));
        var best = valid();
        best.setAction("reprompt");
        best.setOnExhausted("deliver-best");
        assertTrue(refused(best).contains("no best call to deliver"));
        var message = valid();
        message.setBlockMessage("x".repeat(1001));
        assertTrue(refused(message).contains("blockMessage is longer than 1000"));
    }

    @Test
    @DisplayName("the JSON shape deserializes onto the task, inherited fields included")
    void deserializes() throws Exception {
        String json = "{\"toolCallGuardrails\":[{\"tools\":[\"mcp:add_issue_comment\"],\"fields\":[\"/body\"],\"type\":\"judge\","
                + "\"action\":\"reprompt\",\"maxRetries\":1,\"blockMessage\":\"no\",\"parameters\":{\"failMode\":\"closed\"}}]}";
        var task = new ObjectMapper().readValue(json, LlmConfiguration.Task.class);
        var entry = task.getToolCallGuardrails().get(0);
        assertEquals(List.of("mcp:add_issue_comment"), entry.getTools());
        assertEquals(List.of("/body"), entry.getFields());
        assertEquals("judge", entry.getType());
        assertEquals("reprompt", entry.getAction());
        assertEquals(1, entry.getMaxRetries());
        assertEquals("closed", entry.getParameters().get("failMode"));
        assertDoesNotThrow(() -> ToolCallGuardrailConfigValidator.validate(task.getToolCallGuardrails(), PATH));
    }

    @Test
    @DisplayName("at most 32 entries; a null entry is refused")
    void entries() {
        List<ToolCallGuardrailConfig> many = new ArrayList<>(Collections.nCopies(33, valid()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ToolCallGuardrailConfigValidator.validate(many, PATH)).getMessage()
                .contains("at most 32"));
        List<ToolCallGuardrailConfig> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> ToolCallGuardrailConfigValidator.validate(withNull, PATH));
    }
}
