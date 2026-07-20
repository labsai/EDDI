/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;
import ai.labs.eddi.engine.api.IRestGroupConversation.DiscussRequest;
import ai.labs.eddi.engine.api.IRestGroupConversation.FollowUpRequest;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestGroupConversation}.
 */
class RestGroupConversationTest {

    private IGroupConversationService groupService;
    private IJsonSerialization jsonSerialization;
    private SecurityIdentity identity;
    private OwnershipValidator ownershipValidator;
    private RestGroupConversation restGroupConversation;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var hitlAccessGuard = new ai.labs.eddi.engine.hitl.HitlAccessGuard(
                identity, ownershipValidator,
                mock(ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore.class),
                mock(ai.labs.eddi.engine.api.IConversationService.class),
                groupService);
        restGroupConversation = new RestGroupConversation(
                groupService, jsonSerialization, identity, ownershipValidator, hitlAccessGuard);
    }

    @Nested
    @DisplayName("discuss")
    class Discuss {

        @Test
        @DisplayName("should return 201 with location header on success")
        void successfulDiscussion() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            when(groupService.discuss("group-1", "What is AI?", "user-1", 0)).thenReturn(gc);

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("What is AI?", "user-1"));

            assertEquals(201, response.getStatus());
            assertNotNull(response.getLocation());
            assertTrue(response.getLocation().toString().contains("gc-1"));
        }

        @Test
        @DisplayName("should use 'anonymous' when userId is null")
        void anonymousUser() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-2");
            when(groupService.discuss("group-1", "Hello", "anonymous", 0)).thenReturn(gc);

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("Hello", null));

            assertEquals(201, response.getStatus());
            verify(groupService).discuss("group-1", "Hello", "anonymous", 0);
        }

        @Test
        @DisplayName("routes inline attachments through the attachment-aware overload")
        @SuppressWarnings("unchecked")
        void discussWithAttachments() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-3");
            when(groupService.discuss(eq("group-1"), eq("Q"), eq("user-1"), eq(0), isNull(), anyList()))
                    .thenReturn(gc);

            var req = new DiscussRequest("Q", "user-1",
                    List.of(new AttachmentRef("image/png", "aGVsbG8=", null, "a.png")));
            Response response = restGroupConversation.discuss("group-1", req);

            assertEquals(201, response.getStatus());
            ArgumentCaptor<List<Attachment>> captor = ArgumentCaptor.forClass(List.class);
            verify(groupService).discuss(eq("group-1"), eq("Q"), eq("user-1"), eq(0), isNull(), captor.capture());
            assertEquals(1, captor.getValue().size());
            assertEquals("aGVsbG8=", captor.getValue().get(0).getBase64Data());
            assertEquals("image/png", captor.getValue().get(0).getMimeType());
            verify(groupService, never()).discuss("group-1", "Q", "user-1", 0);
        }

        @Test
        @DisplayName("empty attachments use the plain overload")
        void discussWithEmptyAttachments() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-4");
            when(groupService.discuss("group-1", "Q", "user-1", 0)).thenReturn(gc);

            restGroupConversation.discuss("group-1", new DiscussRequest("Q", "user-1", List.of()));

            verify(groupService).discuss("group-1", "Q", "user-1", 0);
        }

        @Test
        @DisplayName("should return 400 for GroupDepthExceededException")
        void depthExceeded() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new IGroupConversationService.GroupDepthExceededException("Max depth reached"));

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("Question", "user-1"));

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should return 404 for ResourceNotFoundException")
        void groupNotFound() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Group not found"));

            Response response = restGroupConversation.discuss("nonexistent",
                    new DiscussRequest("Question", "user-1"));

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should propagate unexpected exceptions")
        void unexpectedException() throws Exception {
            when(groupService.discuss(anyString(), anyString(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Unexpected"));

            assertThrows(RuntimeException.class,
                    () -> restGroupConversation.discuss("group-1",
                            new DiscussRequest("Q", "u")));
        }
    }

    @Nested
    @DisplayName("readGroupConversation")
    class ReadGroupConversation {

        @Test
        @DisplayName("should return group conversation")
        void success() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1"); // must belong to the {groupId} in the path (404 otherwise)
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);

            GroupConversation result = restGroupConversation.readGroupConversation("group-1", "gc-1");

            assertEquals("gc-1", result.getId());
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void notFound() throws Exception {
            when(groupService.readGroupConversation("gc-missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-missing"));
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            when(groupService.readGroupConversation("gc-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-1"));
        }
    }

    @Nested
    @DisplayName("deleteGroupConversation")
    class DeleteGroupConversation {

        @Test
        @DisplayName("should return 200 on successful delete")
        void success() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1");
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);

            Response response = restGroupConversation.deleteGroupConversation("group-1", "gc-1");

            assertEquals(200, response.getStatus());
            verify(groupService).deleteGroupConversation("gc-1");
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1");
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);
            doThrow(new IResourceStore.ResourceStoreException("Delete failed"))
                    .when(groupService).deleteGroupConversation("gc-1");

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.deleteGroupConversation("group-1", "gc-1"));
        }
    }

    @Nested
    @DisplayName("listGroupConversations")
    class ListGroupConversations {

        @Test
        @DisplayName("should return list of conversations")
        void success() throws Exception {
            var gc1 = new GroupConversation();
            gc1.setId("gc-1");
            var gc2 = new GroupConversation();
            gc2.setId("gc-2");
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenReturn(List.of(gc1, gc2));

            List<GroupConversation> result = restGroupConversation.listGroupConversations("group-1", 0, 10);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no conversations")
        void empty() throws Exception {
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenReturn(List.of());

            List<GroupConversation> result = restGroupConversation.listGroupConversations("group-1", 0, 10);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should propagate ResourceStoreException")
        void storeError() throws Exception {
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.listGroupConversations("group-1", 0, 10));
        }
    }

    @Nested
    @DisplayName("OwnershipValidation")
    class OwnershipValidation {

        @Test
        @DisplayName("should throw ForbiddenException when caller does not own group conversation (read)")
        void readGroupConversation_rejectsNonOwner() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1");
            gc.setUserId("other-user");
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);
            doThrow(new ForbiddenException("Access denied"))
                    .when(ownershipValidator).requireOwnerOrAdmin(identity, "other-user", "group conversation");

            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-1"));
        }

        @Test
        @DisplayName("should throw ForbiddenException when caller does not own group conversation (delete)")
        void deleteGroupConversation_rejectsNonOwner() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("group-1");
            gc.setUserId("other-user");
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);
            doThrow(new ForbiddenException("Access denied"))
                    .when(ownershipValidator).requireOwnerOrAdmin(identity, "other-user", "group conversation");

            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.deleteGroupConversation("group-1", "gc-1"));
            verify(groupService, never()).deleteGroupConversation(anyString());
        }

        @Test
        @DisplayName("should resolve userId via validator during discuss")
        void discuss_resolvesUserId() throws Exception {
            when(ownershipValidator.validateAndResolveUserId(identity, "user-1"))
                    .thenReturn("admin-resolved");
            var gc = new GroupConversation();
            gc.setId("gc-1");
            when(groupService.discuss("group-1", "Hello", "admin-resolved", 0)).thenReturn(gc);

            Response response = restGroupConversation.discuss("group-1",
                    new DiscussRequest("Hello", "user-1"));

            assertEquals(201, response.getStatus());
            verify(groupService).discuss("group-1", "Hello", "admin-resolved", 0);
        }

        @Test
        @DisplayName("should filter list to owned conversations for non-admin users")
        void listGroupConversations_filtersForNonAdmin() throws Exception {
            when(ownershipValidator.isAuthEnabled()).thenReturn(true);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.hasRole("eddi-admin")).thenReturn(false);
            var principal = mock(java.security.Principal.class);
            when(principal.getName()).thenReturn("user-1");
            when(identity.getPrincipal()).thenReturn(principal);

            var gc1 = new GroupConversation();
            gc1.setId("gc-1");
            gc1.setUserId("user-1");
            var gc2 = new GroupConversation();
            gc2.setId("gc-2");
            gc2.setUserId("other-user");
            when(groupService.listGroupConversations("group-1", 0, 10))
                    .thenReturn(new ArrayList<>(List.of(gc1, gc2)));

            List<GroupConversation> result = restGroupConversation.listGroupConversations("group-1", 0, 10);

            assertEquals(1, result.size());
            assertEquals("gc-1", result.get(0).getId());
        }
    }

    @Nested
    @DisplayName("PostDiscussionOperations (followup / continue / close)")
    class PostDiscussionOperations {

        private GroupConversation gcInGroup(String groupId) {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId(groupId);
            gc.setUserId("user-1");
            return gc;
        }

        @Test
        @DisplayName("followUp — 404 when conversation belongs to a different group")
        void followUp_groupMismatch_returns404() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            assertEquals(404, response.getStatus());
            verify(groupService, never()).followUpWithMember(any(), any(), any());
            verify(ownershipValidator, never()).requireOwnerOrAdmin(any(), any(), any());
        }

        @Test
        @DisplayName("followUp — 404 when conversation has no group set")
        void followUp_nullGroup_returns404() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-1"); // groupId left null
            when(groupService.readGroupConversation("gc-1")).thenReturn(gc);

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("followUp — 409 on GroupDiscussionException (wrong state)")
        void followUp_conflict_returns409() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.followUpWithMember("gc-1", "agentA", "q"))
                    .thenThrow(new IGroupConversationService.GroupDiscussionException("not COMPLETED"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("followUp — 404 (not 409) when the target agent is not a member")
        void followUp_unknownMember_returns404() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.followUpWithMember("gc-1", "ghost", "q"))
                    .thenThrow(new IGroupConversationService.GroupMemberNotFoundException(
                            "The requested agent is not a member of this group conversation. Available members: {}"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "ghost", "user-1"));

            // A typo'd agent is a client error, not a retryable conflict.
            assertEquals(404, response.getStatus());
            assertFalse(String.valueOf(response.getEntity()).contains("ghost"),
                    "the caller-supplied targetAgentId must not be reflected");
        }

        @Test
        @DisplayName("followUp — 502 (not 409) when the member agent call fails")
        void followUp_agentFailure_returns502() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.followUpWithMember("gc-1", "agentA", "q"))
                    .thenThrow(new IGroupConversationService.GroupExecutionException(
                            "Failed to call agent 'agentA': provider 500"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            // An upstream agent/model failure is a 5xx, not a "conflict — retry".
            assertEquals(502, response.getStatus());
        }

        @Test
        @DisplayName("followUp — 504 (not 409) when the member agent times out")
        void followUp_timeout_returns504() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.followUpWithMember("gc-1", "agentA", "q"))
                    .thenThrow(new IGroupConversationService.GroupTimeoutException(
                            "Follow-up timed out for agent 'agentA'", new java.util.concurrent.TimeoutException()));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            assertEquals(504, response.getStatus());
        }

        @Test
        @DisplayName("followUp — 200 and validates ownership before delegating")
        void followUp_success_returns200() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.followUpWithMember("gc-1", "agentA", "q")).thenReturn(gcInGroup("group-1"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "agentA", "user-1"));

            assertEquals(200, response.getStatus());
            verify(ownershipValidator).requireOwnerOrAdmin(identity, "user-1", "group conversation");
        }

        @Test
        @DisplayName("continue — 404 when conversation belongs to a different group")
        void continue_groupMismatch_returns404() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"));

            assertEquals(404, response.getStatus());
            verify(groupService, never()).continueDiscussion(any(), any(), any());
        }

        @Test
        @DisplayName("continue — 200 on success")
        void continue_success_returns200() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion("gc-1", "q", null)).thenReturn(gcInGroup("group-1"));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("continue — 502 (not 409) when a member agent fails mid-round")
        void continue_agentFailure_returns502() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion("gc-1", "q", null))
                    .thenThrow(new IGroupConversationService.GroupExecutionException(
                            "Group discussion failed: provider 500"));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"));

            assertEquals(502, response.getStatus());
        }

        @Test
        @DisplayName("continue — 504 (not 409) when a member agent times out mid-round")
        void continue_timeout_returns504() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion("gc-1", "q", null))
                    .thenThrow(new IGroupConversationService.GroupTimeoutException(
                            "timed out", new java.util.concurrent.TimeoutException()));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"));

            assertEquals(504, response.getStatus());
        }

        @Test
        @DisplayName("continue — still 409 for a genuine state/concurrency conflict")
        void continue_stateConflict_returns409() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.continueDiscussion("gc-1", "q", null))
                    .thenThrow(new IGroupConversationService.GroupDiscussionException("not COMPLETED"));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("q", "user-1"));

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("close — 404 when conversation belongs to a different group")
        void close_groupMismatch_returns404() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));

            Response response = restGroupConversation.closeGroupConversation("group-1", "gc-1");

            assertEquals(404, response.getStatus());
            verify(groupService, never()).closeGroupConversation(any());
        }

        @Test
        @DisplayName("close — 409 on GroupDiscussionException (wrong state / in progress)")
        void close_conflict_returns409() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.closeGroupConversation("gc-1"))
                    .thenThrow(new IGroupConversationService.GroupDiscussionException("in progress"));

            Response response = restGroupConversation.closeGroupConversation("group-1", "gc-1");

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("close — 500 on genuine store failure (not 409)")
        void close_storeError_returns500() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.closeGroupConversation("gc-1"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB down"));

            // ResourceStoreException falls through to the generic mapper (500), not 409
            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> restGroupConversation.closeGroupConversation("group-1", "gc-1"));
        }

        @Test
        @DisplayName("close — 200 on success")
        void close_success_returns200() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            when(groupService.closeGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));

            Response response = restGroupConversation.closeGroupConversation("group-1", "gc-1");

            assertEquals(200, response.getStatus());
        }

        // --- merge-review fixes ---

        @Test
        @DisplayName("followUp — 400 (not 500) when targetAgentId is missing")
        void followUp_missingTargetAgentId_returns400() {
            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("what did you mean?", null, "user-1"));

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("followUp — 400 when question is blank")
        void followUp_blankQuestion_returns400() {
            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("  ", "agentA", "user-1"));

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("continue — 400 when question is blank")
        void continue_blankQuestion_returns400() {
            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1",
                    new DiscussRequest("", "user-1"));

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("continue — attachments are rejected with 400, never silently dropped")
        void continue_rejectsAttachments() throws Exception {
            // A continuation cannot share NEW files: the fan-out grants/injects attachments
            // only on a member's first-ever turn, and on a continuation every member
            // conversation already exists. Reject explicitly instead of pretending.
            var req = new DiscussRequest("round two", "user-1",
                    List.of(new AttachmentRef("image/png", "aGVsbG8=", null, "diagram.png")));

            Response response = restGroupConversation.continueDiscussion("group-1", "gc-1", req);

            assertEquals(400, response.getStatus());
            verify(groupService, never()).continueDiscussion(any(), any(), any());
        }

        @Test
        @DisplayName("delete — 409 (not 500) when another operation is in flight")
        void delete_operationInProgress_returns409() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("group-1"));
            doThrow(new IGroupConversationService.GroupDiscussionException(
                    "Cannot delete: another operation is already in progress for this group conversation"))
                    .when(groupService).deleteGroupConversation("gc-1");

            Response response = restGroupConversation.deleteGroupConversation("group-1", "gc-1");

            assertEquals(409, response.getStatus());
            // The raw exception text must not reach the client (CodeQL: information
            // exposure through an error message) — the body is curated.
            assertFalse(String.valueOf(response.getEntity()).contains("Cannot delete:"),
                    "the raw exception message must not be echoed to the caller");
        }

        @Test
        @DisplayName("malformed id — the storage layer's raw payload is never reflected back")
        void malformedId_doesNotReflectThePayload() throws Exception {
            // Mongo's ObjectId parser puts the RAW caller string in its message. Echoing it
            // (directly, or via the global IllegalArgumentException mapper) is an arbitrary
            // reflected-value sink.
            String payload = "<script>alert(1)</script>";
            when(groupService.readGroupConversation(payload))
                    .thenThrow(new IllegalArgumentException(
                            "invalid hexadecimal representation of an ObjectId: [" + payload + "]"));

            Response response = restGroupConversation.followUpWithMember("group-1", payload,
                    new FollowUpRequest("q", "Analyst", "user-1"));

            assertEquals(404, response.getStatus(), "a malformed id cannot name a conversation");
            assertFalse(String.valueOf(response.getEntity()).contains("script"),
                    "the caller-supplied payload must never be reflected back");
            assertFalse(String.valueOf(response.getEntity()).contains("ObjectId"),
                    "the storage layer's raw message must never be reflected back");
        }

        @Test
        @DisplayName("group mismatch — the thrown 404's MESSAGE carries no groupId (read/delete surface it via the mapper)")
        void groupMismatch_exceptionMessageIsCurated() throws Exception {
            // readGroupConversation/deleteGroupConversation sneakyThrow this exception; the
            // global ResourceNotFoundExceptionMapper echoes getLocalizedMessage() into the
            // body. So the MESSAGE itself — not just the curated bodies of the endpoints
            // that build their own Response — has to be free of caller input.
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));

            var thrown = assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restGroupConversation.readGroupConversation("group-1", "gc-1"));

            assertFalse(String.valueOf(thrown.getMessage()).contains("group-1"),
                    "the exception message reaches the client via the mapper — it must not embed the groupId");
        }

        @Test
        @DisplayName("group mismatch — 404 body does not reflect the caller-supplied groupId")
        void groupMismatch_doesNotReflectGroupId() throws Exception {
            when(groupService.readGroupConversation("gc-1")).thenReturn(gcInGroup("other-group"));

            Response response = restGroupConversation.followUpWithMember("group-1", "gc-1",
                    new FollowUpRequest("q", "Analyst", "user-1"));

            assertEquals(404, response.getStatus());
            assertFalse(String.valueOf(response.getEntity()).contains("group-1"),
                    "the caller-supplied groupId must not be reflected back (CodeQL reflected-value)");
        }
    }
}
