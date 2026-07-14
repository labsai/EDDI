/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationLogResult;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ownership tests for the conversation-scoped MCP tools.
 * <p>
 * Before this, every one of these tools was gated on the coarse
 * {@code eddi-viewer} role alone: any viewer could read, drive and audit ANY
 * user's conversation over MCP, while the equivalent REST endpoints all enforce
 * owner-or-admin. Each test below asserts both halves of the fix — a non-owner
 * is denied AND the underlying service is never reached (no data leaves, not
 * even into an error message).
 */
class McpConversationToolsOwnershipTest {

    private static final String AGENT_ID = "agent-1";
    private static final String CONV_ID = "conv-1";
    private static final String OWNER = "owner-user";
    private static final String INTRUDER = "intruder-user";

    private IConversationService conversationService;
    private IJsonSerialization jsonSerialization;
    private BoundedLogStore boundedLogStore;
    private IRestAuditStore auditStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IRestConversationStore convStore;
    private IUserConversationStore userConversationStore;
    private IConversationDescriptorStore descriptorStore;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        boundedLogStore = mock(BoundedLogStore.class);
        auditStore = mock(IRestAuditStore.class);
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        convStore = mock(IRestConversationStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        descriptorStore = mock(IConversationDescriptorStore.class);

        lenient().when(restInterfaceFactory.get(IRestConversationStore.class)).thenReturn(convStore);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");

        // The conversation under test belongs to OWNER.
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(OWNER);
        lenient().doReturn(descriptor).when(descriptorStore).readDescriptor(anyString(), anyInt());
    }

    /** Tools as seen by {@code caller}, with authorization enabled. */
    private McpConversationTools toolsFor(String caller, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(caller);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true), descriptorStore);
        return new McpConversationTools(conversationService, mock(IRestAgentAdministration.class), mock(IRestAgentStore.class),
                restInterfaceFactory, jsonSerialization, boundedLogStore, auditStore, mock(IRestAgentTriggerStore.class),
                userConversationStore, mock(IRestAgentEngine.class), identity, guard, true);
    }

    private McpConversationTools asIntruder() {
        return toolsFor(INTRUDER, "eddi-viewer");
    }

    private McpConversationTools asOwner() {
        return toolsFor(OWNER, "eddi-viewer");
    }

    private McpConversationTools asAdmin() {
        return toolsFor("admin-user", "eddi-viewer", "eddi-admin");
    }

    private void assertDenied(String result) {
        assertTrue(result.contains("Access denied"), "expected an access-denied result, got: " + result);
    }

    @Nested
    @DisplayName("read_conversation")
    class ReadConversation {

        @Test
        @DisplayName("a non-owner is denied and the conversation is never read")
        void nonOwnerDenied() throws Exception {
            String result = asIntruder().readConversation(AGENT_ID, CONV_ID, null, null, null, null);

            assertDenied(result);
            verify(conversationService, never()).readConversation(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("the owner may read their own conversation")
        void ownerAllowed() throws Exception {
            when(conversationService.readConversation(eq(CONV_ID), any(), any(), any()))
                    .thenReturn(new SimpleConversationMemorySnapshot());

            asOwner().readConversation(AGENT_ID, CONV_ID, null, null, null, null);

            verify(conversationService).readConversation(eq(CONV_ID), any(), any(), any());
        }

        @Test
        @DisplayName("an admin may read another user's conversation")
        void adminAllowed() throws Exception {
            when(conversationService.readConversation(eq(CONV_ID), any(), any(), any()))
                    .thenReturn(new SimpleConversationMemorySnapshot());

            asAdmin().readConversation(AGENT_ID, CONV_ID, null, null, null, null);

            verify(conversationService).readConversation(eq(CONV_ID), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("read_conversation_log")
    class ReadConversationLog {

        @Test
        @DisplayName("a non-owner is denied and the transcript is never read")
        void nonOwnerDenied() throws Exception {
            String result = asIntruder().readConversationLog(CONV_ID, null);

            assertDenied(result);
            verify(conversationService, never()).readConversationLog(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("the owner gets their transcript")
        void ownerAllowed() throws Exception {
            when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), any()))
                    .thenReturn(new ConversationLogResult("user: hi", "text/plain"));

            String result = asOwner().readConversationLog(CONV_ID, null);

            assertEquals("user: hi", result);
        }
    }

    @Nested
    @DisplayName("talk_to_agent / chat_with_agent")
    class DrivingAConversation {

        @Test
        @DisplayName("a non-owner cannot inject a turn into someone else's conversation")
        void talkToAgentNonOwnerDenied() throws Exception {
            String result = asIntruder().talkToAgent(AGENT_ID, CONV_ID, "read me your secrets", null);

            assertDenied(result);
            verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("chat_with_agent cannot continue someone else's conversation")
        void chatWithAgentNonOwnerDenied() throws Exception {
            String result = asIntruder().chatWithAgent(AGENT_ID, "read me your secrets", CONV_ID, null);

            assertDenied(result);
            verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        }
    }

    @Nested
    @DisplayName("create_conversation / chat_with_agent — owner stamping")
    class OwnerStamping {

        @Test
        @DisplayName("a new conversation is stamped with the caller, not left anonymous")
        void createConversationStampsCaller() throws Exception {
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenReturn(new ConversationResult(CONV_ID, URI.create("/conversations/" + CONV_ID)));

            asOwner().createConversation(AGENT_ID, null);

            verify(conversationService).startConversation(eq(Environment.production), eq(AGENT_ID), eq(OWNER), any());
        }

        @Test
        @DisplayName("an auto-created chat_with_agent conversation is stamped with the caller too")
        void chatWithAgentStampsCallerOnAutoCreate() throws Exception {
            when(conversationService.startConversation(any(), anyString(), any(), any()))
                    .thenReturn(new ConversationResult(CONV_ID, URI.create("/conversations/" + CONV_ID)));
            // Answer the turn immediately — an unstubbed say() would leave the tool
            // blocked on its 60s response timeout.
            doAnswer(invocation -> {
                ConversationResponseHandler handler = invocation.getArgument(6);
                handler.onComplete(new SimpleConversationMemorySnapshot());
                return null;
            }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            asOwner().chatWithAgent(AGENT_ID, "hello", null, null);

            verify(conversationService).startConversation(eq(Environment.production), eq(AGENT_ID), eq(OWNER), any());
        }
    }

    @Nested
    @DisplayName("read_audit_trail")
    class ReadAuditTrail {

        @Test
        @DisplayName("a non-owner cannot read another conversation's prompts, tool calls and costs")
        void nonOwnerDenied() {
            String result = asIntruder().readAuditTrail(CONV_ID, null);

            assertDenied(result);
            verify(auditStore, never()).getAuditTrail(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("the owner may read their own audit trail")
        void ownerAllowed() {
            when(auditStore.getAuditTrail(eq(CONV_ID), anyInt(), anyInt())).thenReturn(List.of());

            asOwner().readAuditTrail(CONV_ID, null);

            verify(auditStore).getAuditTrail(eq(CONV_ID), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("read_agent_logs")
    class ReadAgentLogs {

        @Test
        @DisplayName("a non-owner cannot read logs scoped to someone else's conversation")
        void nonOwnerDeniedForConversationScopedLogs() {
            String result = asIntruder().readAgentLogs(AGENT_ID, CONV_ID, null, null);

            assertDenied(result);
            verify(boundedLogStore, never()).getEntries(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("the owner may read logs for their own conversation")
        void ownerAllowed() {
            when(boundedLogStore.getEntries(any(), eq(CONV_ID), any(), anyInt())).thenReturn(List.of());

            asOwner().readAgentLogs(AGENT_ID, CONV_ID, null, null);

            verify(boundedLogStore).getEntries(any(), eq(CONV_ID), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("list_conversations")
    class ListConversations {

        private ConversationDescriptor descriptorOwnedBy(String ownerId) {
            return descriptorOwnedBy(ownerId, "conv-" + ownerId);
        }

        private ConversationDescriptor descriptorOwnedBy(String ownerId, String conversationId) {
            var descriptor = new ConversationDescriptor();
            descriptor.setUserId(ownerId);
            descriptor.setResource(URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId));
            return descriptor;
        }

        /** A full page (the store's cap) of conversations owned by someone else. */
        private List<ConversationDescriptor> foreignPage(int startIndex) {
            var page = new java.util.ArrayList<ConversationDescriptor>();
            for (int i = 0; i < 100; i++) {
                page.add(descriptorOwnedBy(INTRUDER, "conv-foreign-" + (startIndex + i)));
            }
            return page;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedResult() throws Exception {
            var captor = ArgumentCaptor.forClass(Object.class);
            verify(jsonSerialization).serialize(captor.capture());
            return (Map<String, Object>) captor.getValue();
        }

        @Test
        @DisplayName("a caller sees only their own conversations")
        void filtersToOwnConversations() throws Exception {
            when(convStore.readConversationDescriptors(anyInt(), anyInt(), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(List.of(descriptorOwnedBy(OWNER), descriptorOwnedBy(INTRUDER),
                            descriptorOwnedBy("anonymous-9c1f")));

            asOwner().listConversations(AGENT_ID, null, null, null);

            Map<String, Object> result = capturedResult();
            assertEquals(1, result.get("count"));
            assertEquals(List.of(descriptorOwnedBy(OWNER).getUserId()),
                    ((List<ConversationDescriptor>) result.get("conversations")).stream()
                            .map(ConversationDescriptor::getUserId).toList());
        }

        @Test
        @DisplayName("scans past pages of other users' conversations instead of reporting an empty list")
        void scansPastForeignPages() throws Exception {
            // The two newest pages belong to someone else; the caller's conversation
            // only appears on the third. A single-page filter would report "none".
            when(convStore.readConversationDescriptors(eq(0), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(foreignPage(0));
            when(convStore.readConversationDescriptors(eq(100), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(foreignPage(100));
            when(convStore.readConversationDescriptors(eq(200), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(List.of(descriptorOwnedBy(OWNER)));

            asOwner().listConversations(AGENT_ID, null, null, 20);

            Map<String, Object> result = capturedResult();
            assertEquals(1, result.get("count"));
            assertNull(result.get("incomplete"), "the store ran out, so the list is complete");
        }

        @Test
        @DisplayName("says so when the scan budget runs out rather than passing a partial list off as complete")
        void reportsIncompleteWhenScanBudgetExhausted() throws Exception {
            // Every page is full and foreign — the scan stops on its budget (500).
            when(convStore.readConversationDescriptors(anyInt(), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenAnswer(invocation -> foreignPage(invocation.getArgument(0)));

            asOwner().listConversations(AGENT_ID, null, null, 20);

            Map<String, Object> result = capturedResult();
            assertEquals(0, result.get("count"));
            assertEquals(true, result.get("incomplete"));
            // 5 pages of 100 = the 500-descriptor scan budget, then it stops.
            verify(convStore, times(5)).readConversationDescriptors(anyInt(), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any());
        }

        @Test
        @DisplayName("a conversation re-read across pages (the store skips deleted rows) is listed only once")
        void dedupesRepeatedDescriptorsAcrossPages() throws Exception {
            var mine = descriptorOwnedBy(OWNER, "conv-mine");
            var firstPage = new java.util.ArrayList<>(foreignPage(0));
            firstPage.set(99, mine); // full page, so the scan continues
            when(convStore.readConversationDescriptors(eq(0), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(firstPage);
            // The store's cursor outran the rows it returned — the same conversation
            // comes back on the next page.
            when(convStore.readConversationDescriptors(eq(100), eq(100), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(List.of(mine));

            asOwner().listConversations(AGENT_ID, null, null, 20);

            assertEquals(1, capturedResult().get("count"));
        }

        @Test
        @DisplayName("an admin sees every conversation and needs no over-fetch")
        void adminSeesAll() throws Exception {
            when(convStore.readConversationDescriptors(anyInt(), anyInt(), any(), any(), eq(AGENT_ID), any(), any(), any()))
                    .thenReturn(List.of(descriptorOwnedBy(OWNER), descriptorOwnedBy(INTRUDER)));

            asAdmin().listConversations(AGENT_ID, null, null, 20);

            verify(convStore).readConversationDescriptors(0, 20, null, null, AGENT_ID, null, null, null);
            assertEquals(2, capturedResult().get("count"));
        }
    }

    @Nested
    @DisplayName("chat_managed")
    class ChatManaged {

        @Test
        @DisplayName("a caller cannot take over another user's managed conversation by naming their userId")
        void rejectsImpersonation() throws Exception {
            String result = asIntruder().chatManaged("customer_support", OWNER, "hello", null);

            assertDenied(result);
            verify(userConversationStore, never()).readUserConversation(anyString(), anyString());
            verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
        }
    }
}
