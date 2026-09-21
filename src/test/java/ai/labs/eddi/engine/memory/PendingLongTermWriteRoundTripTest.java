/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deferred user-memory write markers are the ONLY record that a
 * {@code longTerm} property living in {@code conversationProperties} has not
 * reached {@code IUserMemoryStore} yet — the next turn takes its "already
 * persisted" baseline from those very properties. If the marker does not
 * round-trip through the conversation document, the write is dropped
 * permanently and silently (HITL pause, error, or store failure).
 */
class PendingLongTermWriteRoundTripTest {

    private static final String OWED_KEY = "dietary_restriction";

    private static ConversationMemory memoryWithOwedWrite() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getConversationProperties().put(OWED_KEY, new Property(OWED_KEY, "vegan", Scope.longTerm));
        memory.setPendingLongTermWrites(Set.of(OWED_KEY));
        // conversion needs at least one step datum
        memory.getCurrentStep().storeData(new Data<>("input", "I am vegan"));
        return memory;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    @DisplayName("memory → snapshot carries the deferred write markers")
    void memoryToSnapshot() {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memoryWithOwedWrite());

        assertEquals(Set.of(OWED_KEY), new LinkedHashSet<>(snapshot.getPendingLongTermWrites()),
                "an owed user-memory write must be recorded on the persisted snapshot");
    }

    @Test
    @DisplayName("snapshot → memory restores the deferred write markers")
    void snapshotToMemory() {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memoryWithOwedWrite());

        var restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

        assertEquals(Set.of(OWED_KEY), new LinkedHashSet<>(restored.getPendingLongTermWrites()),
                "the reloaded conversation must still know the write is owed");
    }

    @Test
    @DisplayName("the markers survive JSON serialization of the conversation document")
    void survivesJsonSerialization() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setPendingLongTermWrites(new LinkedHashSet<>(Set.of(OWED_KEY)));

        String json = mapper().writeValueAsString(snapshot);
        assertTrue(json.contains(OWED_KEY), "expected the owed key in the serialized document: " + json);

        var reread = mapper().readValue(json, ConversationMemorySnapshot.class);
        assertEquals(Set.of(OWED_KEY), new LinkedHashSet<>(reread.getPendingLongTermWrites()));
    }

    @Test
    @DisplayName("a pre-6.2 document without the field deserializes to an empty marker set, not null")
    void legacyDocumentWithoutTheFieldStillLoads() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setPendingLongTermWrites(new LinkedHashSet<>(Set.of(OWED_KEY)));

        // Simulate a document written before the field existed.
        var node = (ObjectNode) mapper().readTree(mapper().writeValueAsString(snapshot));
        node.remove("pendingLongTermWrites");
        var legacy = mapper().treeToValue(node, ConversationMemorySnapshot.class);

        assertNotNull(legacy.getPendingLongTermWrites());
        assertTrue(legacy.getPendingLongTermWrites().isEmpty());
        assertTrue(ConversationMemoryUtilities.convertConversationMemorySnapshot(legacy).getPendingLongTermWrites().isEmpty());
    }
}
