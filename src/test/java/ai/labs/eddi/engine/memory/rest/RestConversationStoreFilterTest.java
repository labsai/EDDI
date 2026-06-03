/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.memory.IAttachmentStorage;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for BUG-3: RestConversationStore agent filtering.
 * <p>
 * Before the fix, agent filtering used {@code getResource()} (the
 * conversation's own resource URI) to extract the agentId, which extracted the
 * conversation ID instead. The fix changed it to use {@code getAgentResource()}
 * which actually contains the agent's URI.
 */
class RestConversationStoreFilterTest {

    private RestConversationStore restConversationStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;

    private static final String AGENT_ID = "5262b802dc6c4008b54c7c0b58100f97";
    private static final String OTHER_AGENT_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final String CONVERSATION_ID = "deadbeefcafebabedeadbeefcafebabe";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        IUserMemoryStore userMemoryStore = mock(IUserMemoryStore.class);
        IRuntime runtime = mock(IRuntime.class);
        Instance<IAttachmentStorage> attachmentStorageInstance = mock(Instance.class);
        when(attachmentStorageInstance.isResolvable()).thenReturn(false);

        restConversationStore = new RestConversationStore(
                documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, userMemoryStore, runtime,
                30, 90, attachmentStorageInstance);
    }

    private ConversationDescriptor createDescriptor(String conversationId, String agentId, int agentVersion) {
        var descriptor = new ConversationDescriptor();
        // The conversation's own resource URI (contains conversationId, NOT agentId)
        descriptor.setResource(URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId + "?version=1"));
        // The agent resource URI (contains the actual agentId)
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agents/agentstore/agents/" + agentId + "?version=" + agentVersion));
        descriptor.setLastModifiedOn(new Date());
        return descriptor;
    }

    private ConversationMemorySnapshot createSnapshot(String agentId, int agentVersion) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(agentId);
        snapshot.setAgentVersion(agentVersion);
        snapshot.setUserId("test-user");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        return snapshot;
    }

    /**
     * BUG-3: When filtering by agentId, the code must use getAgentResource() (which
     * contains the agent URI) — not getResource() (which contains the conversation
     * URI). This test verifies that a descriptor whose agentResource matches the
     * filter is included in the results.
     */
    @Test
    void readConversationDescriptors_agentIdFilter_usesAgentResource() throws Exception {
        // Arrange
        var descriptor = createDescriptor(CONVERSATION_ID, AGENT_ID, 1);

        when(conversationDescriptorStore.readDescriptors(eq("ai.labs.conversation"), any(), eq(0), eq(20), eq(false)))
                .thenReturn(List.of(descriptor));
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createSnapshot(AGENT_ID, 1));

        var docDescriptor = new DocumentDescriptor();
        docDescriptor.setName("Test Agent");
        when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(docDescriptor);

        // Act — filter by AGENT_ID
        var results = restConversationStore.readConversationDescriptors(
                0, 20, null, null, AGENT_ID, null, null, null);

        // Assert — descriptor with matching agentResource should be included
        assertEquals(1, results.size());
    }

    /**
     * Verify that a descriptor belonging to a different agent is filtered out when
     * filtering by agentId.
     */
    @Test
    void readConversationDescriptors_agentIdFilter_filtersNonMatchingAgent() throws Exception {
        // Arrange — descriptor belongs to OTHER_AGENT_ID
        var descriptor = createDescriptor(CONVERSATION_ID, OTHER_AGENT_ID, 1);

        when(conversationDescriptorStore.readDescriptors(eq("ai.labs.conversation"), any(), eq(0), eq(20), eq(false)))
                .thenReturn(List.of(descriptor));
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createSnapshot(OTHER_AGENT_ID, 1));

        var docDescriptor = new DocumentDescriptor();
        docDescriptor.setName("Other Agent");
        when(documentDescriptorStore.readDescriptor(OTHER_AGENT_ID, 1)).thenReturn(docDescriptor);

        // Act — filter by AGENT_ID (should NOT match OTHER_AGENT_ID)
        var results = restConversationStore.readConversationDescriptors(
                0, 20, null, null, AGENT_ID, null, null, null);

        // Assert — descriptor with non-matching agentResource should be filtered
        assertEquals(0, results.size());
    }

    /**
     * When filtering by agentId AND agentVersion, a descriptor whose agentResource
     * has the matching version should be included.
     */
    @Test
    void readConversationDescriptors_agentVersionFilter_matchesCorrectVersion() throws Exception {
        var descriptor = createDescriptor(CONVERSATION_ID, AGENT_ID, 1);

        when(conversationDescriptorStore.readDescriptors(eq("ai.labs.conversation"), any(), eq(0), eq(20), eq(false)))
                .thenReturn(List.of(descriptor));
        // Intentionally different from descriptor agentResource version to ensure
        // filtering is based on agentResource, not snapshot metadata.
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createSnapshot(AGENT_ID, 99));

        var docDescriptor = new DocumentDescriptor();
        docDescriptor.setName("Test Agent");
        when(documentDescriptorStore.readDescriptor(AGENT_ID, 99)).thenReturn(docDescriptor);

        // Act — filter by AGENT_ID + version 1
        var results = restConversationStore.readConversationDescriptors(
                0, 20, null, null, AGENT_ID, 1, null, null);

        // Assert — matching version should be included
        assertEquals(1, results.size());
    }

    /**
     * When filtering by agentId AND agentVersion, a descriptor whose agentResource
     * has a different version should be excluded.
     */
    @Test
    void readConversationDescriptors_agentVersionFilter_filtersWrongVersion() throws Exception {
        // Descriptor is version 1, but we filter for version 2
        var descriptor = createDescriptor(CONVERSATION_ID, AGENT_ID, 1);

        when(conversationDescriptorStore.readDescriptors(eq("ai.labs.conversation"), any(), eq(0), eq(20), eq(false)))
                .thenReturn(List.of(descriptor));
        // Intentionally different from descriptor agentResource version to ensure
        // filtering is based on agentResource, not snapshot metadata.
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createSnapshot(AGENT_ID, 99));

        var docDescriptor = new DocumentDescriptor();
        docDescriptor.setName("Test Agent");
        when(documentDescriptorStore.readDescriptor(AGENT_ID, 99)).thenReturn(docDescriptor);

        // Act — filter by AGENT_ID + version 2 (descriptor is version 1)
        var results = restConversationStore.readConversationDescriptors(
                0, 20, null, null, AGENT_ID, 2, null, null);

        // Assert — wrong version should be filtered out
        assertEquals(0, results.size());
    }
}
