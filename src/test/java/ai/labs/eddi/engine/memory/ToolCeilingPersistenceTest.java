/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The tool ceiling of an email sender role (email plan v3 §9) is part of the
 * stored conversation: a HITL resume next week runs under it. And it only ever
 * narrows.
 */
@DisplayName("ConversationMemory — the tool ceiling")
class ToolCeilingPersistenceTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    @DisplayName("memory -> snapshot -> JSON -> snapshot -> memory keeps the ceiling")
    void roundTrip() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "email:x:ann@customer-a.com");
        memory.getCurrentStep().storeData(new Data<>("input", "hi"));
        memory.setToolCeiling(Set.of("kb_search", "lookup_order"));

        String json = mapper().writeValueAsString(ConversationMemoryUtilities.convertConversationMemory(memory));
        var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(mapper().readValue(json, ConversationMemorySnapshot.class));

        assertEquals(Set.of("kb_search", "lookup_order"), restored.getToolCeiling());
    }

    @Test
    @DisplayName("an empty ceiling (no tools at all) is kept as empty, not as none")
    void emptyIsKept() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "email-anon:1");
        memory.getCurrentStep().storeData(new Data<>("input", "hi"));
        memory.setToolCeiling(Set.of());

        String json = mapper().writeValueAsString(ConversationMemoryUtilities.convertConversationMemory(memory));
        var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(mapper().readValue(json, ConversationMemorySnapshot.class));

        assertEquals(Set.of(), restored.getToolCeiling());
    }

    @Test
    @DisplayName("a later ceiling narrows the stored one; none never widens it; old documents have none")
    void onlyNarrows() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "u");
        memory.setToolCeiling(Set.of("a", "b"));
        memory.setToolCeiling(null);
        assertEquals(Set.of("a", "b"), memory.getToolCeiling());
        memory.setToolCeiling(Set.of("b", "c"));
        assertEquals(Set.of("b"), memory.getToolCeiling());

        assertNull(mapper().readValue("{\"conversationState\":\"READY\"}", ConversationMemorySnapshot.class).getToolCeiling());
    }
}
