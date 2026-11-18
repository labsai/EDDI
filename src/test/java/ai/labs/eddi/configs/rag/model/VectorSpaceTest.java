/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VectorSpace")
class VectorSpaceTest {

    private static RagConfiguration config(String provider, Map<String, String> params) {
        RagConfiguration config = new RagConfiguration();
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }

    private static VectorSpace space(String provider, String model) {
        return VectorSpace.of(config(provider, Map.of("model", model)), Map.of("model", model));
    }

    @Test
    @DisplayName("another model is another space; the same model is not, whatever its case")
    void modelDecides() {
        assertTrue(space("openai", "emb-8").conflictsWith(space("openai", "other-8")));
        assertFalse(space("openai", "emb-8").conflictsWith(space("OpenAI", "EMB-8")));
    }

    @Test
    @DisplayName("another provider is another space")
    void providerDecides() {
        assertTrue(space("openai", "m").conflictsWith(space("ollama", "m")));
    }

    @Test
    @DisplayName("model and its alias modelName are one setting")
    void modelAlias() {
        VectorSpace viaModel = VectorSpace.of(config("openai", null), Map.of("model", "m"));
        VectorSpace viaAlias = VectorSpace.of(config("openai", null), Map.of("modelName", "m"));
        assertFalse(viaModel.conflictsWith(viaAlias));
    }

    @Test
    @DisplayName("an unknown vector size is not a conflict; two different known sizes are")
    void dimension() {
        VectorSpace unknown = space("openai", "m");
        assertFalse(unknown.conflictsWith(unknown.withDimension(768)));
        assertFalse(unknown.withDimension(768).conflictsWith(unknown));
        assertTrue(unknown.withDimension(768).conflictsWith(unknown.withDimension(1536)));
        assertFalse(unknown.withDimension(768).conflictsWith(unknown.withDimension(768)));
    }

    @Test
    @DisplayName("the configured dimensions and Gemini's outputDimensionality set the size")
    void configuredSize() {
        assertEquals(256, VectorSpace.of(config("openai", null), Map.of("model", "m", "dimensions", "256")).dimension());
        assertEquals(512, VectorSpace.of(config("gemini", null), Map.of("model", "m", "outputDimensionality", "512")).dimension());
        assertNull(VectorSpace.of(config("openai", null), Map.of("model", "m", "dimensions", "${vars:size}")).dimension());
    }

    @Test
    @DisplayName("an Azure deployment and a Gemini task type are part of the space")
    void deploymentAndTaskType() {
        VectorSpace a = VectorSpace.of(config("azure-openai", null), Map.of("deploymentName", "one"));
        VectorSpace b = VectorSpace.of(config("azure-openai", null), Map.of("deploymentName", "two"));
        assertTrue(a.conflictsWith(b));
        VectorSpace doc = VectorSpace.of(config("gemini", null), Map.of("model", "g", "taskType", "RETRIEVAL_DOCUMENT"));
        VectorSpace sim = VectorSpace.of(config("gemini", null), Map.of("model", "g", "taskType", "SEMANTIC_SIMILARITY"));
        assertTrue(doc.conflictsWith(sim));
    }

    @Test
    @DisplayName("credentials and endpoints are not part of the space")
    void credentialsAndEndpointsAreNot() {
        Map<String, String> before = new HashMap<>(Map.of("model", "m", "apiKey", "k1", "baseUrl", "http://a"));
        Map<String, String> after = new HashMap<>(Map.of("model", "m", "apiKey", "k2", "baseUrl", "http://b"));
        assertFalse(VectorSpace.of(config("openai", before), before).conflictsWith(VectorSpace.of(config("openai", after), after)));
    }

    @Test
    @DisplayName("a missing provider is OpenAI, the configuration's default")
    void defaultProvider() {
        assertEquals("openai", VectorSpace.of(config(null, null), Map.of()).provider());
    }

    @Test
    @DisplayName("encode and decode round-trip, and foreign text decodes to null")
    void roundTrip() {
        VectorSpace original = VectorSpace.of(config("gemini", null), Map.of("model", "g|x", "taskType", "T")).withDimension(768);
        assertEquals(original, VectorSpace.decode(original.encode()));
        assertNull(VectorSpace.decode("store-epoch=7"));
        assertNull(VectorSpace.decode("v9|a|b|c|d|1"));
        assertNull(VectorSpace.decode(null));
    }

    @Test
    @DisplayName("describe names the model and size and nothing secret")
    void describe() {
        assertEquals("openai / emb-8, 8 dimensions", space("openai", "emb-8").withDimension(8).describe());
        assertEquals("openai / default model, dimension not yet known",
                VectorSpace.of(config("openai", null), Map.of("apiKey", "secret")).describe());
    }
}
