/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for BUG-2: GroupConversationService.extractResponse()
 * metadata detection.
 * <p>
 * The fix adds metadata-only detection: when conversation output only contains
 * pipeline metadata keys like 'actions', 'input', 'context' (and no actual
 * LLM-generated text), extractResponse() returns null instead of serializing
 * raw pipeline internals as the agent's response.
 * <p>
 * Since extractResponse() is private, we test the detection logic pattern
 * directly. The logic checks whether all keys in the output map are metadata
 * keys that don't represent actual agent output.
 */
class GroupConversationServiceExtractResponseTest {

    /**
     * Evaluates the metadata-only detection predicate. This mirrors the logic in
     * GroupConversationService.extractResponse():
     *
     * <pre>
     * boolean hasOnlyMetadata = lastOutput.keySet().stream()
     *         .allMatch(k -> k instanceof String s &&
     *                 (s.equals("actions") || s.equals("input") || s.equals("context")));
     * </pre>
     */
    private boolean hasOnlyMetadata(Map<?, ?> output) {
        return output.keySet().stream()
                .allMatch(k -> k instanceof String s &&
                        (s.equals("actions") || s.equals("input") || s.equals("context")));
    }

    /**
     * When output contains only 'actions' and 'input' (typical for a turn where the
     * LLM didn't produce text output), it should be detected as metadata-only.
     */
    @Test
    void metadataDetection_onlyActionsAndInput_detected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet"));
        output.put("input", "hello");

        assertTrue(hasOnlyMetadata(output),
                "Map with only 'actions' and 'input' should be detected as metadata-only");
    }

    /**
     * When output contains 'actions', 'input', AND 'output', it should NOT be
     * detected as metadata-only because 'output' contains actual LLM text.
     */
    @Test
    void metadataDetection_withOutput_notDetected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet"));
        output.put("input", "hello");
        output.put("output", List.of(Map.of("text", "Hello! How can I help?")));

        assertFalse(hasOnlyMetadata(output),
                "Map with 'output' key should NOT be detected as metadata-only");
    }

    /**
     * An empty map trivially satisfies allMatch (vacuous truth), so it should be
     * detected as metadata-only. This is correct behavior because an empty output
     * means no LLM text was produced.
     */
    @Test
    void metadataDetection_empty_detected() {
        Map<String, Object> output = new LinkedHashMap<>();

        assertTrue(hasOnlyMetadata(output),
                "Empty map should be detected as metadata-only (vacuous truth)");
    }

    /**
     * When output contains only 'context', it should be metadata-only.
     */
    @Test
    void metadataDetection_onlyContext_detected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("context", Map.of("language", "en"));

        assertTrue(hasOnlyMetadata(output),
                "Map with only 'context' should be detected as metadata-only");
    }

    /**
     * All three metadata keys together should still be metadata-only.
     */
    @Test
    void metadataDetection_allThreeMetadataKeys_detected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet", "respond"));
        output.put("input", "hello world");
        output.put("context", Map.of("lang", "en"));

        assertTrue(hasOnlyMetadata(output),
                "Map with all three metadata keys should be detected as metadata-only");
    }

    /**
     * Output with a custom key (e.g., 'output:text:myOutput') is NOT metadata.
     */
    @Test
    void metadataDetection_withCustomOutputKey_notDetected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("respond"));
        output.put("input", "hello");
        output.put("output:text:myOutput", "Hello, I'm here to help!");

        assertFalse(hasOnlyMetadata(output),
                "Map with 'output:text:*' key should NOT be detected as metadata-only");
    }

    /**
     * Non-String keys should cause the predicate to return false (not metadata).
     */
    @Test
    void metadataDetection_nonStringKey_notDetected() {
        Map<Object, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet"));
        output.put(42, "numeric key");

        assertFalse(hasOnlyMetadata(output),
                "Map with non-String keys should NOT be detected as metadata-only");
    }
}
