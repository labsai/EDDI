/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ConversationSetup}.
 */
@DisplayName("ConversationSetup")
class ConversationSetupTest {

    private IConversationDescriptorStore conversationDescriptorStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private ConversationSetup setup;

    @BeforeEach
    void setUp() {
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        setup = new ConversationSetup(conversationDescriptorStore, documentDescriptorStore);
    }

    // ==================== createConversationDescriptor ====================

    @Nested
    @DisplayName("createConversationDescriptor")
    class CreateConversationDescriptorTests {

        @Test
        @DisplayName("creates descriptor with agent name from document descriptor")
        void happyPath() throws Exception {
            var agent = mock(IAgent.class);
            doReturn("agent-1").when(agent).getAgentId();
            doReturn(1).when(agent).getAgentVersion();

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("My Agent");
            doReturn(agentDescriptor).when(documentDescriptorStore).readDescriptor("agent-1", 1);

            URI conversationUri = URI.create("/conversationstore/conversations/conv-1");

            setup.createConversationDescriptor("agent-1", agent, "user-1", "conv-1", conversationUri);

            verify(documentDescriptorStore).readDescriptor("agent-1", 1);
            verify(conversationDescriptorStore).createDescriptor(eq("conv-1"), eq(0), argThat(desc -> {
                assertEquals("My Agent", desc.getAgentName());
                return true;
            }));
        }

        @Test
        @DisplayName("agent descriptor with null name — sets null agentName")
        void nullAgentName() throws Exception {
            var agent = mock(IAgent.class);
            doReturn("agent-2").when(agent).getAgentId();
            doReturn(2).when(agent).getAgentVersion();

            var agentDescriptor = new DocumentDescriptor();
            // name is null
            doReturn(agentDescriptor).when(documentDescriptorStore).readDescriptor("agent-2", 2);

            URI conversationUri = URI.create("/conversationstore/conversations/conv-2");

            setup.createConversationDescriptor("agent-2", agent, "user-1", "conv-2", conversationUri);

            verify(conversationDescriptorStore).createDescriptor(eq("conv-2"), eq(0), argThat(desc -> {
                assertNull(desc.getAgentName());
                return true;
            }));
        }
    }

    // ==================== computeAnonymousUserIdIfEmpty ====================

    @Nested
    @DisplayName("computeAnonymousUserIdIfEmpty")
    class ComputeAnonymousUserIdTests {

        @Test
        @DisplayName("non-empty userId is returned as-is")
        void nonEmptyUserId() {
            String result = setup.computeAnonymousUserIdIfEmpty("user-123", null);
            assertEquals("user-123", result);
        }

        @Test
        @DisplayName("non-empty userId with non-null context still returns userId")
        void nonEmptyUserIdWithContext() {
            var context = new Context();
            context.setValue("context-user");
            String result = setup.computeAnonymousUserIdIfEmpty("user-123", context);
            assertEquals("user-123", result);
        }

        @Test
        @DisplayName("null userId with String context value — returns context value")
        void nullUserId_stringContext() {
            var context = new Context();
            context.setValue("context-user-id");
            String result = setup.computeAnonymousUserIdIfEmpty(null, context);
            assertEquals("context-user-id", result);
        }

        @Test
        @DisplayName("empty userId with String context value — returns context value")
        void emptyUserId_stringContext() {
            var context = new Context();
            context.setValue("context-user-id");
            String result = setup.computeAnonymousUserIdIfEmpty("", context);
            assertEquals("context-user-id", result);
        }

        @Test
        @DisplayName("null userId with null context — returns anonymous-UUID")
        void nullUserId_nullContext() {
            String result = setup.computeAnonymousUserIdIfEmpty(null, null);
            assertTrue(result.startsWith("anonymous-"));
            // UUID without dashes = 32 hex chars
            assertEquals(42, result.length()); // "anonymous-" (10) + 32 hex
        }

        @Test
        @DisplayName("null userId with non-String context value — returns anonymous-UUID")
        void nullUserId_nonStringContextValue() {
            var context = new Context();
            context.setValue(42); // Integer, not String
            String result = setup.computeAnonymousUserIdIfEmpty(null, context);
            assertTrue(result.startsWith("anonymous-"));
        }

        @Test
        @DisplayName("empty userId with null context — returns anonymous-UUID")
        void emptyUserId_nullContext() {
            String result = setup.computeAnonymousUserIdIfEmpty("", null);
            assertTrue(result.startsWith("anonymous-"));
        }
    }
}
