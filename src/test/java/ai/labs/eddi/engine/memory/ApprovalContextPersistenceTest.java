/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rendered approval context is a first-class persisted field next to the
 * HITL bookmark: it must survive the serializer, and be withheld from the raw
 * conversation read and the simple snapshot.
 */
@DisplayName("Approval context persistence")
class ApprovalContextPersistenceTest {

    private static RenderedApprovalContext context() {
        return RenderedApprovalContext.of(List.of(new Item("Draft", "Acme cuts onboarding to 3 days", false, "agent", null, null),
                new Item("Post text", "hello", true, "rule:mcp:publish_*", "call-1", null)), true);
    }

    private static ConversationMemory pausedMemory() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("input", "publish it"));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlApprovalContext(context());
        return memory;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    @DisplayName("memory -> snapshot -> JSON -> snapshot -> memory keeps items, flag and digest")
    void roundTripThroughTheSerializer() throws Exception {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(pausedMemory());
        assertNotNull(snapshot.getHitlApprovalContext());

        String json = mapper().writeValueAsString(snapshot);
        assertTrue(json.contains("\"hitlApprovalContext\""));
        var restored = mapper().readValue(json, ConversationMemorySnapshot.class);
        var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(restored);

        RenderedApprovalContext back = memory.getHitlApprovalContext();
        assertNotNull(back);
        assertEquals(2, back.getItems().size());
        assertEquals("Draft", back.getItems().get(0).getLabel());
        assertEquals("Acme cuts onboarding to 3 days", back.getItems().get(0).getText());
        assertEquals("rule:mcp:publish_*", back.getItems().get(1).getSource());
        assertEquals("call-1", back.getItems().get(1).getCallId());
        assertTrue(back.getItems().get(1).isTruncated());
        assertTrue(back.isTruncated());
        assertEquals(context().getDigest(), back.getDigest());
    }

    @Test
    @DisplayName("an absent context reads back null, also from a document written before the field existed")
    void absentStaysAbsent() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>("input", "x"));
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
        assertNull(snapshot.getHitlApprovalContext());

        var old = mapper().readValue("{\"conversationState\":\"READY\"}", ConversationMemorySnapshot.class);
        assertNull(old.getHitlApprovalContext());
        assertNull(ConversationMemoryUtilities.convertConversationMemorySnapshot(old).getHitlApprovalContext());
    }

    @Test
    @DisplayName("the raw conversation read withholds the context")
    void rawReadStripsIt() {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(pausedMemory());
        assertNotNull(snapshot.getHitlApprovalContext());

        ConversationMemoryUtilities.redactRawPendingToolCallsForRead(snapshot);

        assertNull(snapshot.getHitlApprovalContext());
    }

    @Test
    @DisplayName("the simple snapshot never carries it")
    void simpleSnapshotHasNoContext() throws Exception {
        var snapshot = ConversationMemoryUtilities.convertConversationMemory(pausedMemory());
        SimpleConversationMemorySnapshot simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, true, false);
        String json = mapper().writeValueAsString(simple);
        assertFalse(json.contains("hitlApprovalContext"), json);
        assertFalse(json.contains("Acme cuts onboarding"), json);
    }
}
