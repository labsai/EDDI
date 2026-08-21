/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationOutputUtilsTest {

    @Test
    void extractOutputText_singleTextEntry() {
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", "Hello world")));
        assertEquals("Hello world", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_multipleTextEntries_joinedWithSpace() {
        var output = new ConversationOutput();
        output.put("output", List.of(
                Map.of("text", "Hello"),
                Map.of("text", "world")));
        assertEquals("Hello world", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_noOutputKey_returnsNull() {
        var output = new ConversationOutput();
        assertNull(ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_emptyList_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", List.of());
        assertNull(ConversationOutputUtils.extractOutputText(output));
    }

    /**
     * A bare String under {@code output} is what
     * {@code addConversationOutputString("output", …)} stores, so it is real agent
     * output rather than a malformed value — this previously returned null.
     */
    @Test
    void extractOutputText_plainStringValue_isExtracted() {
        var output = new ConversationOutput();
        output.put("output", "not a list");
        assertEquals("not a list", ConversationOutputUtils.extractOutputText(output));
    }

    /**
     * Was {@code extractOutputText_listOfNonMaps_returnsNull}, which pinned the
     * defect: a plain String is exactly what
     * {@code addConversationOutputString(...)} writes, so returning null here
     * silently erased every HITL-gated turn from the log, the summary, the recall
     * tool and the agent's own history.
     */
    @Test
    void extractOutputText_listOfPlainStrings_extractsThem() {
        var output = new ConversationOutput();
        output.put("output", List.of("just a string"));
        assertEquals("just a string", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_hitlShapedList_stringsThenMap_extractsAll() {
        // The literal shape observed on a stored HITL turn: STRING, STRING, OBJECT.
        var output = new ConversationOutput();
        output.put("output", List.of(
                "I'll create that group for you.",
                "Waiting for your approval.",
                Map.of("text", "Group created.", "type", "text", "delay", 0)));
        assertEquals("I'll create that group for you. Waiting for your approval. Group created.",
                ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_mapFirstThenString_stillExtractsTheString() {
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", "first"), "second"));
        assertEquals("first second", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_blankStringsAreNotOutput() {
        var output = new ConversationOutput();
        output.put("output", List.of("   ", "real"));
        assertEquals("real", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_mapsWithoutTextKey_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("other", "value")));
        assertNull(ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_mixedMapsWithAndWithoutText() {
        var output = new ConversationOutput();
        output.put("output", List.of(
                Map.of("text", "Hello"),
                Map.of("other", "ignored"),
                Map.of("text", "world")));
        assertEquals("Hello world", ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_nullOutputValue_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", null);
        assertNull(ConversationOutputUtils.extractOutputText(output));
    }
}
