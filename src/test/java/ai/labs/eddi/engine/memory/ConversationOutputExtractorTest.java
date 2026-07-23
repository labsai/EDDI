/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationOutputExtractor} — the shared utility that
 * extracts human-readable text from conversation memory snapshots.
 */
class ConversationOutputExtractorTest {

    // --- Null / empty input ---

    @Test
    void extractResponse_nullSnapshot_returnsNull() {
        assertNull(ConversationOutputExtractor.extractResponse(null));
    }

    @Test
    void extractResponse_nullOutputs_returnsNull() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(null);
        assertNull(ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_emptyOutputs_returnsNull() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(new LinkedList<>());
        assertNull(ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Format 1: Nested "output" array with plain strings ---

    @Test
    void extractResponse_outputArrayWithStrings_joinsThem() {
        var output = new ConversationOutput();
        output.put("output", List.of("Hello", "World"));
        var snapshot = snapshotWith(output);

        assertEquals("Hello\nWorld", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_outputArrayWithSingleString() {
        var output = new ConversationOutput();
        output.put("output", List.of("Only one"));
        var snapshot = snapshotWith(output);

        assertEquals("Only one", ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Format 1: Nested "output" array with Map items (text key) ---

    @Test
    void extractResponse_outputArrayWithMapItems_extractsTextKey() {
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", "From map"), Map.of("text", "Another")));
        var snapshot = snapshotWith(output);

        assertEquals("From map\nAnother", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_outputArrayWithMixedItems() {
        var output = new ConversationOutput();
        output.put("output", List.of("Plain text", Map.of("text", "Map text")));
        var snapshot = snapshotWith(output);

        assertEquals("Plain text\nMap text", ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Format 2: Flat keys like "output:text:*" ---

    @Test
    void extractResponse_flatOutputTextKey_extractsValue() {
        var output = new ConversationOutput();
        output.put("output:text:greeting", "Hello from flat key");
        var snapshot = snapshotWith(output);

        assertEquals("Hello from flat key", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_flatOutputTextKey_withListValue() {
        var output = new ConversationOutput();
        output.put("output:text:items", List.of("First", "Second"));
        var snapshot = snapshotWith(output);

        assertEquals("First\nSecond", ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Metadata-only output (no "output" or "reply" keys) ---

    @Test
    void extractResponse_metadataOnly_returnsNull() {
        var output = new ConversationOutput();
        output.put("actions", List.of("greet"));
        output.put("input", "Hello");
        var snapshot = snapshotWith(output);

        assertNull(ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- No extractable text: output key exists but content is not text ---

    @Test
    void extractResponse_nonListOutputKey_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", 42); // Not a List → falls through format 1
        var snapshot = snapshotWith(output);

        // No recognizable text format → should return null, not toString()
        assertNull(ConversationOutputExtractor.extractResponse(snapshot),
                "Non-list output value should not produce a toString() dump");
    }

    @Test
    void extractResponse_outputListWithNullItems_returnsNull() {
        // This is the exact scenario from production: the pipeline produces
        // output=[null] when the langchain step has no output extension to
        // copy the LLM response into conversationOutputs.
        var nullList = new LinkedList<>();
        nullList.add(null);
        var output = new ConversationOutput();
        output.put("output", nullList);
        output.put("actions", List.of("send_message", "unknown"));
        var snapshot = snapshotWith(output);

        assertNull(ConversationOutputExtractor.extractResponse(snapshot),
                "output=[null] should return null, not dump raw metadata");
    }

    // --- Format 1b: Plain "output" value (String or Map) ---

    @Test
    void extractResponse_outputAsPlainString() {
        var output = new ConversationOutput();
        output.put("output", "Direct string response");
        var snapshot = snapshotWith(output);

        assertEquals("Direct string response", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_outputAsMapWithTextKey() {
        var output = new ConversationOutput();
        output.put("output", Map.of("text", "From output map"));
        var snapshot = snapshotWith(output);

        assertEquals("From output map", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_blankOutputString_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", "   ");
        var snapshot = snapshotWith(output);

        assertNull(ConversationOutputExtractor.extractResponse(snapshot),
                "Blank output string should not be treated as meaningful text");
    }

    // --- Format 3: "reply" key ---

    @Test
    void extractResponse_replyAsString() {
        var output = new ConversationOutput();
        output.put("reply", "Reply text");
        var snapshot = snapshotWith(output);

        assertEquals("Reply text", ConversationOutputExtractor.extractResponse(snapshot));
    }

    @Test
    void extractResponse_replyAsList() {
        var output = new ConversationOutput();
        output.put("reply", List.of("Reply line 1", "Reply line 2"));
        var snapshot = snapshotWith(output);

        assertEquals("Reply line 1\nReply line 2", ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Multiple outputs: always uses the LAST one ---

    @Test
    void extractResponse_multipleOutputs_usesLast() {
        var first = new ConversationOutput();
        first.put("output", List.of("First output"));
        var second = new ConversationOutput();
        second.put("output", List.of("Second output"));
        var outputs = new LinkedList<ConversationOutput>();
        outputs.add(first);
        outputs.add(second);

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(outputs);

        assertEquals("Second output", ConversationOutputExtractor.extractResponse(snapshot));
    }

    // --- Helper ---

    private static SimpleConversationMemorySnapshot snapshotWith(ConversationOutput output) {
        var outputs = new LinkedList<ConversationOutput>();
        outputs.add(output);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(outputs);
        return snapshot;
    }
}
