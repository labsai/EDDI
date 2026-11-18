/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The {@code documents} block survives a save round trip unchanged: what comes
 * in is what goes out, an absent block stays absent, and an LLM configuration
 * without the block serializes exactly as before.
 */
@DisplayName("DocumentsConfig — round trip")
class DocumentsConfigRoundTripTest {

    /** The mapper the stores use. */
    private static final ObjectMapper MAPPER = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

    private static JsonNode roundTrip(String json) throws Exception {
        // Re-read from the written text, so that a number compares by value and not by
        // node type (an int literal reads as IntNode, a Long field writes as LongNode).
        return MAPPER.readTree(MAPPER.writeValueAsString(MAPPER.readValue(json, DocumentsConfig.class)));
    }

    /**
     * {@code json} with every {@code null} member removed — what NON_NULL writes.
     */
    private static JsonNode withoutNulls(String json) throws Exception {
        JsonNode tree = MAPPER.readTree(json);
        stripNulls(tree);
        return tree;
    }

    private static void stripNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> nulls = new ArrayList<>();
            object.fields().forEachRemaining(e -> {
                if (e.getValue().isNull()) {
                    nulls.add(e.getKey());
                } else {
                    stripNulls(e.getValue());
                }
            });
            object.remove(nulls);
        }
    }

    @Test
    @DisplayName("the whole design JSON comes back as it went in (explicit nulls dropped)")
    void fullDesignJson() throws Exception {
        assertEquals(withoutNulls(DocumentsDesignJson.FULL), roundTrip(DocumentsDesignJson.FULL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"split\":{}}", "{\"large\":{\"summary\":{}}}", "{\"bridge\":{}}", "{\"library\":{\"scopes\":[]}}",
            "{\"sharing\":{\"delegation\":false}}"})
    @DisplayName("a partial block comes back exactly")
    void partialBlocks(String json) throws Exception {
        assertEquals(MAPPER.readTree(json), roundTrip(json));
    }

    @Test
    @DisplayName("a new DocumentsConfig serializes to {} — no field has an initializer")
    void noInitializers() throws Exception {
        assertEquals("{}", MAPPER.writeValueAsString(new DocumentsConfig()));
        assertEquals("{}", MAPPER.writeValueAsString(new DocumentsConfig.Large.Summary()));
    }

    @Test
    @DisplayName("an LLM configuration without documents serializes byte-identical after a round trip")
    void llmConfigWithoutDocuments() throws Exception {
        String json = MAPPER.writeValueAsString(MAPPER.readValue("""
                {"tasks":[{"actions":["help"],"id":"t1","type":"openai","parameters":{"modelName":"gpt-x"}}]}
                """, LlmConfiguration.class));
        String again = MAPPER.writeValueAsString(MAPPER.readValue(json, LlmConfiguration.class));
        assertEquals(json, again);
        assertFalse(json.contains("documents"), json);
        assertNull(MAPPER.readValue(json, LlmConfiguration.class).tasks().get(0).getDocuments());
    }

    @Test
    @DisplayName("the strict REST boundary accepts every design key (the refusal is the validator's, not a 400 for an unknown key)")
    void strictBoundaryAcceptsTheDesignShape() throws Exception {
        var strict = new StrictConfigurationParser(MAPPER);
        String body = "{\"tasks\":[{\"actions\":[\"help\"],\"type\":\"openai\",\"documents\":" + DocumentsDesignJson.FULL + "}]}";
        LlmConfiguration parsed = assertDoesNotThrow(() -> strict.parse(body, LlmConfiguration.class));
        assertEquals(withoutNulls(DocumentsDesignJson.FULL), MAPPER.readTree(MAPPER.writeValueAsString(parsed.tasks().get(0).getDocuments())));
    }
}
