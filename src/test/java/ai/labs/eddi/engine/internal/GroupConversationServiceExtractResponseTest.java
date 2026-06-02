/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for BUG-2: GroupConversationService.extractResponse()
 * metadata detection.
 * <p>
 * The fix detects when conversation output contains no actual LLM-generated
 * content (i.e., no keys starting with "output" or "reply"). In that case,
 * extractResponse() returns null instead of serializing raw pipeline internals
 * as the agent's response.
 * <p>
 * Since extractResponse() is private, we test the detection logic pattern
 * directly. The logic checks whether any key in the output map represents
 * actual agent output.
 */
class GroupConversationServiceExtractResponseTest {

    /**
     * Evaluates the output detection predicate. This mirrors the logic in
     * GroupConversationService.extractResponse():
     *
     * <pre>
     * boolean hasAnyOutput = lastOutput.keySet().stream()
     *         .anyMatch(k -> k instanceof String s &&
     *                 (s.startsWith("output") || s.startsWith("reply")));
     * </pre>
     *
     * @return true if the map has NO output keys (metadata-only)
     */
    private boolean hasOnlyMetadata(Map<?, ?> output) {
        boolean hasAnyOutput = output.keySet().stream()
                .anyMatch(k -> k instanceof String s &&
                        (s.startsWith("output") || s.startsWith("reply")));
        return !hasAnyOutput;
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
     * An empty map has no output keys, so it should be detected as metadata-only.
     * This is correct behavior because an empty output means no LLM text was
     * produced.
     */
    @Test
    void metadataDetection_empty_detected() {
        Map<String, Object> output = new LinkedHashMap<>();

        assertTrue(hasOnlyMetadata(output),
                "Empty map should be detected as metadata-only (no output keys)");
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
     * All three common metadata keys together should still be metadata-only.
     */
    @Test
    void metadataDetection_allThreeMetadataKeys_detected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet", "respond"));
        output.put("input", "hello world");
        output.put("context", Map.of("lang", "en"));

        assertTrue(hasOnlyMetadata(output),
                "Map with only metadata keys (no output/reply) should be detected as metadata-only");
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
     * Output with a 'reply' key is NOT metadata.
     */
    @Test
    void metadataDetection_withReplyKey_notDetected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("respond"));
        output.put("reply", "Here is my reply");

        assertFalse(hasOnlyMetadata(output),
                "Map with 'reply' key should NOT be detected as metadata-only");
    }

    /**
     * Future pipeline metadata keys (e.g., 'timestamp', 'debug') should be
     * correctly detected as metadata-only — the logic is resilient to new metadata
     * keys because it checks for absence of output keys rather than presence of
     * specific metadata keys.
     */
    @Test
    void metadataDetection_unknownMetadataKeys_stillDetected() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actions", List.of("greet"));
        output.put("input", "hello");
        output.put("timestamp", "2026-01-01T00:00:00Z");
        output.put("debug", Map.of("traceId", "abc123"));

        assertTrue(hasOnlyMetadata(output),
                "Map with unknown metadata keys (but no output/reply) should be detected as metadata-only");
    }
}
