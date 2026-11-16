/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which embedding parameters are refused (they would redirect data), which are
 * only logged, and how the model name is read.
 */
class EmbeddingParametersBranchTest {

    private static Map<String, String> params(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected, got " + keyValues.length + " values");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void anIgnoredEndpointParameterIsRefusedCaseInsensitively() {
        String problem = EmbeddingParameters.findProblem(" bedrock ", params("baseURL", "https://private.example", "region", "eu-west-1"));
        assertTrue(problem.startsWith("embeddingParameters.baseURL is not supported by embedding provider 'bedrock'"), problem);
        assertTrue(problem.contains("[dimensions, model, modelName, region]"), problem);
    }

    @Test
    void everyEndpointSpellingIsRefusedWhereItIsIgnored() {
        // Vertex honours "endpoint"; "apiEndpoint" is the chat builder's spelling and
        // would be silently dropped here.
        String problem = EmbeddingParameters.findProblem("vertex", params("apiEndpoint", "europe-west4-aiplatform.googleapis.com:443"));
        assertTrue(problem != null && problem.startsWith("embeddingParameters.apiEndpoint is not supported"), problem);
        String hostname = EmbeddingParameters.findProblem("bedrock", params("hostname", "bedrock.private.example"));
        assertTrue(hostname != null && hostname.startsWith("embeddingParameters.hostname is not supported"), hostname);
    }

    @Test
    void anHonouredEndpointParameterIsFine() {
        assertNull(EmbeddingParameters.findProblem("openai", params("baseUrl", "https://proxy.example/v1", "model", "text-embedding-3-small")));
        assertNull(EmbeddingParameters.findProblem("azure-openai", params("endpoint", "https://x.openai.azure.com")));
    }

    @Test
    void unknownProvidersAndMissingParametersAreLeftToOtherChecks() {
        assertNull(EmbeddingParameters.findIgnoredEndpointParameter("acme", params("url", "x")));
        assertNull(EmbeddingParameters.findIgnoredEndpointParameter(null, params("url", "x")));
        assertNull(EmbeddingParameters.findIgnoredEndpointParameter("openai", null));
        assertEquals(Set.of(), EmbeddingParameters.honoured(null));
        assertEquals(Set.of(), EmbeddingParameters.honoured("acme"));
        assertTrue(EmbeddingParameters.honoured(" ollama ").contains("baseUrl"));
    }

    @Test
    void aNullKeyAndHarmlessLeftoversAreNotRefused() {
        Map<String, String> withNullKey = params("leftover", "1");
        withNullKey.put(null, "x");
        assertNull(EmbeddingParameters.findIgnoredEndpointParameter("ollama", params("leftover", "1", "model", "nomic")));
        assertEquals(Set.of("leftover"), EmbeddingParameters.unrecognised("ollama", withNullKey));
    }

    @Test
    void unrecognisedListsOnlyKeysTheProviderIgnores() {
        assertEquals(Set.of("temperature", "url"), EmbeddingParameters.unrecognised("cohere", params("temperature", "1", "url", "x", "apiKey", "k")));
        assertEquals(Set.of(), EmbeddingParameters.unrecognised("acme", params("x", "1")));
        assertEquals(Set.of(), EmbeddingParameters.unrecognised(null, params("x", "1")));
        assertEquals(Set.of(), EmbeddingParameters.unrecognised("cohere", null));
    }

    @Test
    void modelAndModelNameMayNotDisagree() {
        String conflict = EmbeddingParameters.findProblem("openai", params("model", "a", "modelName", "b"));
        assertTrue(conflict.contains("sets both 'model' ('a') and 'modelName' ('b')"), conflict);
        assertNull(EmbeddingParameters.findModelNameConflict(params("model", "a", "modelName", "a")));
        assertNull(EmbeddingParameters.findModelNameConflict(params("model", " ", "modelName", "b")));
        assertNull(EmbeddingParameters.findModelNameConflict(params("model", "a", "modelName", " ")));
        assertNull(EmbeddingParameters.findModelNameConflict(params("modelName", "b")));
        assertNull(EmbeddingParameters.findModelNameConflict(params("model", "a")));
        assertNull(EmbeddingParameters.findModelNameConflict(null));
    }

    @Test
    void theModelNameIsModelThenModelNameThenTheDefault() {
        assertEquals("a", EmbeddingParameters.modelName(params("model", "a", "modelName", "b"), "d"));
        assertEquals("b", EmbeddingParameters.modelName(params("model", " ", "modelName", "b"), "d"));
        assertEquals("d", EmbeddingParameters.modelName(params("modelName", " "), "d"));
        assertEquals("d", EmbeddingParameters.modelName(params(), "d"));
        assertEquals("d", EmbeddingParameters.modelName(null, "d"));
    }
}
