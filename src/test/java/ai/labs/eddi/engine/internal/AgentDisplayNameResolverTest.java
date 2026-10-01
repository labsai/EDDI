/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chat user holding only {@code eddi-user} can see the name of the agent they
 * talk to — through the conversation read — and still cannot read the agent's
 * descriptor or configuration.
 */
class AgentDisplayNameResolverTest {

    /** A hex id, so id extraction never turns it into null. */
    private static final String AGENT_ID = "65a1b2c3d4e5f6a7b8c9d0e1";
    private static final String CONVERSATION_ID = "75a1b2c3d4e5f6a7b8c9d0e2";

    private IConversationService conversationService;
    private ResourceAccessGuard resourceAccessGuard;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        resourceAccessGuard = mock(ResourceAccessGuard.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);

        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        var conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        var owned = new ConversationDescriptor();
        owned.setUserId("synthetic-user");
        when(conversationDescriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(owned);
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, conversationDescriptorStore);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, conversationDescriptorStore, conversationService,
                mock(IGroupConversationService.class));

        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, resourceAccessGuard, hitlAccessGuard, mock(IHitlToolJournalStore.class),
                new AgentDisplayNameResolver(resourceAccessGuard, documentDescriptorStore), 30);

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(3);
        when(conversationService.readConversation(CONVERSATION_ID, false, true, List.of())).thenReturn(snapshot);
    }

    private static DocumentDescriptor descriptorNamed(String name) {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        descriptor.setDescription("internal notes about how this agent is built");
        return descriptor;
    }

    @Nested
    @DisplayName("the conversation read")
    class ConversationRead {

        @Test
        @DisplayName("carries the agent's name for a caller who may use the agent")
        void carriesTheName() throws Exception {
            when(resourceAccessGuard.hasAccess(AGENT_ID, AccessLevel.USE)).thenReturn(true);
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenReturn(descriptorNamed("Support Bot"));

            var snapshot = restAgentEngine.readConversation(CONVERSATION_ID, false, true, List.of());

            assertEquals("Support Bot", snapshot.getAgentName());
        }

        @Test
        @DisplayName("carries the name and nothing else from the descriptor")
        void carriesOnlyTheName() throws Exception {
            when(resourceAccessGuard.hasAccess(AGENT_ID, AccessLevel.USE)).thenReturn(true);
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenReturn(descriptorNamed("Support Bot"));

            var snapshot = restAgentEngine.readConversation(CONVERSATION_ID, false, true, List.of());
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            String json = mapper.writeValueAsString(snapshot);

            assertTrue(json.contains("\"agentName\":\"Support Bot\""), json);
            assertFalse(json.contains("internal notes"), "the descriptor's description reached the chat: " + json);
        }

        @Test
        @DisplayName("carries no name, and reads no descriptor, for a caller who may not use the agent")
        void noNameWithoutUseAccess() throws Exception {
            when(resourceAccessGuard.hasAccess(AGENT_ID, AccessLevel.USE)).thenReturn(false);
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenReturn(descriptorNamed("Support Bot"));

            var snapshot = restAgentEngine.readConversation(CONVERSATION_ID, false, true, List.of());

            assertNull(snapshot.getAgentName());
            verify(documentDescriptorStore, never()).readDescriptor(any(), any());
        }

        @Test
        @DisplayName("still succeeds, without a name, when the descriptor cannot be read")
        void unreadableDescriptorIsNotAFailure() throws Exception {
            when(resourceAccessGuard.hasAccess(AGENT_ID, AccessLevel.USE)).thenReturn(true);
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenThrow(new ResourceStoreException("store down"));

            var snapshot = restAgentEngine.readConversation(CONVERSATION_ID, false, true, List.of());

            assertEquals(CONVERSATION_ID, snapshot.getConversationId());
            assertNull(snapshot.getAgentName());
        }

        @Test
        @DisplayName("carries no name when the descriptor's name is blank")
        void blankNameIsNoName() throws Exception {
            when(resourceAccessGuard.hasAccess(AGENT_ID, AccessLevel.USE)).thenReturn(true);
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenReturn(descriptorNamed("  "));

            assertNull(restAgentEngine.readConversation(CONVERSATION_ID, false, true, List.of()).getAgentName());
        }
    }

    /**
     * There is no role hierarchy on REST: each endpoint lists every role it
     * accepts. So "eddi-user can read the name" and "eddi-user cannot read the
     * configuration" are both facts about these annotations.
     */
    @Nested
    @DisplayName("the role gates")
    class RoleGates {

        private static List<String> classRoles(Class<?> type) {
            return Arrays.asList(type.getAnnotation(RolesAllowed.class).value());
        }

        @Test
        @DisplayName("eddi-user may read a conversation, which now carries the name")
        void eddiUserMayReadTheConversation() throws Exception {
            Method read = IRestAgentEngine.class.getMethod("readConversation", String.class, Boolean.class, Boolean.class, List.class);
            assertTrue(read.isAnnotationPresent(GET.class));
            RolesAllowed override = read.getAnnotation(RolesAllowed.class);
            List<String> roles = override != null ? Arrays.asList(override.value()) : classRoles(IRestAgentEngine.class);
            assertTrue(roles.contains("eddi-user"), "roles: " + roles);
        }

        @Test
        @DisplayName("eddi-user may read neither the descriptor store nor the agent configuration")
        void eddiUserMayNotReadConfiguration() {
            for (Class<?> store : List.of(IRestDocumentDescriptorStore.class, IRestAgentStore.class)) {
                assertFalse(classRoles(store).contains("eddi-user"), store.getSimpleName() + " admits eddi-user");
                for (Method method : store.getMethods()) {
                    RolesAllowed override = method.getAnnotation(RolesAllowed.class);
                    if (override != null) {
                        assertFalse(Arrays.asList(override.value()).contains("eddi-user"),
                                store.getSimpleName() + "." + method.getName() + " admits eddi-user");
                    }
                }
            }
        }
    }
}
