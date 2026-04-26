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

    @Test
    void extractOutputText_nonListValue_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", "not a list");
        assertNull(ConversationOutputUtils.extractOutputText(output));
    }

    @Test
    void extractOutputText_listOfNonMaps_returnsNull() {
        var output = new ConversationOutput();
        output.put("output", List.of("just a string"));
        assertNull(ConversationOutputUtils.extractOutputText(output));
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
