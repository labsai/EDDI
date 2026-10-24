/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stored-config back-compat of
 * {@code inputGuardrails}/{@code outputGuardrails}: a pre-guardrail
 * {@code langchain.json} loads with both fields null (the LLM task then runs no
 * guardrail code), and a guardrail config round-trips through the persistence
 * mapper and passes the strict REST boundary.
 */
@DisplayName("GuardrailConfig — deserialization and back-compat")
class GuardrailConfigDeserializationTest {

    private final ObjectMapper persistence = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

    private static final String PRE_GUARDRAIL_JSON = """
            { "tasks": [ {
                "actions": [ "help" ],
                "id": "support",
                "type": "openai",
                "parameters": { "systemMessage": "You are helpful", "addToOutput": "true" }
            } ] }
            """;

    private static final String GUARDED_JSON = """
            { "tasks": [ {
                "actions": [ "support" ],
                "id": "support",
                "type": "openai",
                "inputGuardrails": [
                  { "type": "prompt-injection", "action": "block", "blockMessage": "I can't process that request." },
                  { "type": "pii-redaction", "action": "redact", "parameters": { "patterns": "email,ssn" } }
                ],
                "outputGuardrails": [
                  { "type": "pii-redaction", "action": "redact" },
                  { "type": "json-object", "action": "reprompt", "maxRetries": 9 }
                ]
            } ] }
            """;

    @Test
    @DisplayName("a langchain.json without guardrails deserializes with both lists null")
    void absentGuardrails_deserializeToNull() throws Exception {
        var config = persistence.readValue(PRE_GUARDRAIL_JSON, LlmConfiguration.class);
        var task = config.tasks().get(0);
        assertNull(task.getInputGuardrails());
        assertNull(task.getOutputGuardrails());
    }

    @Test
    @DisplayName("an old document round-trips without growing guardrail members")
    void absentGuardrails_roundTripWithoutNewMembers() throws Exception {
        var config = persistence.readValue(PRE_GUARDRAIL_JSON, LlmConfiguration.class);
        var written = persistence.writeValueAsString(config);
        var reread = persistence.readValue(written, LlmConfiguration.class);
        assertNull(reread.tasks().get(0).getInputGuardrails());
        assertNull(reread.tasks().get(0).getOutputGuardrails());
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) ((List<?>) persistence.readValue(written, Map.class).get("tasks")).get(0);
        assertNull(task.get("inputGuardrails"));
    }

    @Test
    @DisplayName("guardrail configs deserialize with defaults applied and maxRetries clamped")
    void guardrails_deserialize() throws Exception {
        var task = persistence.readValue(GUARDED_JSON, LlmConfiguration.class).tasks().get(0);
        assertEquals(2, task.getInputGuardrails().size());
        assertEquals("prompt-injection", task.getInputGuardrails().get(0).getType());
        assertEquals("I can't process that request.", task.getInputGuardrails().get(0).getBlockMessage());
        assertEquals(Map.of("patterns", "email,ssn"), task.getInputGuardrails().get(1).getParameters());

        GuardrailConfig redact = task.getOutputGuardrails().get(0);
        assertEquals("redact", redact.getAction());
        assertNull(redact.getMaxRetries());
        assertEquals(GuardrailConfig.DEFAULT_MAX_RETRIES, redact.effectiveMaxRetries());

        GuardrailConfig reprompt = task.getOutputGuardrails().get(1);
        assertEquals(GuardrailConfig.MAX_RETRIES_CEILING, reprompt.effectiveMaxRetries());
    }

    @Test
    @DisplayName("an unset action defaults to block")
    void unsetAction_isBlock() throws Exception {
        var config = persistence.readValue("{\"type\":\"max-length\"}", GuardrailConfig.class);
        assertEquals("block", config.getAction());
        assertEquals(GuardrailAction.BLOCK, GuardrailAction.parse(config.getAction()));
    }

    @Test
    @DisplayName("an unknown member in a stored guardrail config is ignored")
    void unknownFields_areIgnored() {
        assertDoesNotThrow(() -> persistence.readValue("{\"type\":\"regex-filter\",\"futureKnob\":true}", GuardrailConfig.class));
    }

    @Test
    @DisplayName("the strict REST boundary accepts a guardrail config")
    void strictBoundary_acceptsGuardrails() {
        var parser = new StrictConfigurationParser(new ObjectMapper());
        assertDoesNotThrow(() -> parser.parse(GUARDED_JSON, LlmConfiguration.class));
    }

    @Test
    @DisplayName("onExhausted round-trips, passes the strict REST boundary, and is not written when unset")
    void onExhausted_roundTrips() throws Exception {
        String json = "{ \"tasks\": [ { \"actions\": [\"a\"], \"id\": \"t\", \"type\": \"openai\", \"outputGuardrails\": [ "
                + "{ \"type\": \"judge\", \"action\": \"reprompt\", \"maxRetries\": 1, \"onExhausted\": \"deliver-best\" } ] } ] }";
        assertDoesNotThrow(() -> new StrictConfigurationParser(new ObjectMapper()).parse(json, LlmConfiguration.class));
        var config = persistence.readValue(json, LlmConfiguration.class).tasks().get(0).getOutputGuardrails().get(0);
        assertEquals("deliver-best", config.getOnExhausted());
        assertTrue(config.deliversBest());
        String written = persistence.writeValueAsString(persistence.readValue(json, LlmConfiguration.class));
        assertTrue(written.contains("\"onExhausted\":\"deliver-best\""), written);
        assertFalse(written.contains("deliversBest"), written);
        assertFalse(persistence.writeValueAsString(persistence.readValue(GUARDED_JSON, LlmConfiguration.class)).contains("onExhausted"));
    }

    @Test
    @DisplayName("no langchain4j type name or 'pass' action is ever persisted")
    void serializedForm_isEddiVocabularyOnly() throws Exception {
        var written = persistence.writeValueAsString(persistence.readValue(GUARDED_JSON, LlmConfiguration.class));
        assertFalse(written.contains("dev.langchain4j"));
        assertFalse(written.contains("\"pass\""));
        assertFalse(written.contains("effectiveMaxRetries"));
    }
}
