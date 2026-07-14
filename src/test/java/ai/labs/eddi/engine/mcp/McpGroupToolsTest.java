/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpGroupTools} — MCP tools for group management and
 * discussion orchestration.
 */
class McpGroupToolsTest {

    private IRestAgentGroupStore groupStore;
    private IGroupConversationService groupConversationService;
    private IJsonSerialization jsonSerialization;
    private McpGroupTools tools;

    @BeforeEach
    void setUp() throws Exception {
        groupStore = mock(IRestAgentGroupStore.class);
        groupConversationService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");

        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);
        // authorization disabled — OwnershipValidator's checks are no-ops, matching the
        // pre-existing tests. Ownership enforcement is covered separately below.
        tools = new McpGroupTools(groupStore, groupConversationService, jsonSerialization, mockIdentity,
                new OwnershipValidator(false), false);
    }

    // --- describe_discussion_styles ---

    @Test
    void describeDiscussionStyles_returnsNonEmptyDescription() {
        String result = tools.describe_discussion_styles();

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertTrue(result.contains("ROUND_TABLE"));
        assertTrue(result.contains("PEER_REVIEW"));
        assertTrue(result.contains("DEVIL_ADVOCATE"));
        assertTrue(result.contains("DELPHI"));
        assertTrue(result.contains("DEBATE"));
        assertTrue(result.contains("TASK_FORCE"));
    }

    // --- list_groups ---

    @Test
    void listGroups_returnsDescriptors() throws Exception {
        when(groupStore.readGroupDescriptors("", 0, 20)).thenReturn(List.of(new DocumentDescriptor()));
        when(jsonSerialization.serialize(any())).thenReturn("[{}]");

        String result = tools.list_groups(null, null, null);

        assertNotNull(result);
        verify(groupStore).readGroupDescriptors("", 0, 20);
    }

    @Test
    void listGroups_withFilterAndPaging() throws Exception {
        when(groupStore.readGroupDescriptors("test", 2, 10)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.list_groups("test", "2", "10");

        verify(groupStore).readGroupDescriptors("test", 2, 10);
    }

    @Test
    void listGroups_handlesException() {
        when(groupStore.readGroupDescriptors(any(), anyInt(), anyInt())).thenThrow(new RuntimeException("DB down"));

        String result = tools.list_groups(null, null, null);

        assertTrue(result.contains("error"));
    }

    // --- read_group ---

    @Test
    void readGroup_success() throws Exception {
        when(groupStore.getCurrentVersion("g1")).thenReturn(1);
        when(groupStore.readGroup("g1", 1)).thenReturn(new AgentGroupConfiguration());

        tools.read_group("g1", "0");

        verify(groupStore).readGroup("g1", 1);
    }

    @Test
    void readGroup_specificVersion() throws Exception {
        when(groupStore.readGroup("g1", 3)).thenReturn(new AgentGroupConfiguration());

        tools.read_group("g1", "3");

        verify(groupStore).readGroup("g1", 3);
    }

    // --- create_group ---

    @Test
    void createGroup_defaultStyle_usesRoundTable() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/new-id?version=1")).build());

        String result = tools.create_group("Panel", "desc", "a1,a2", "Alice,Bob", null, null, null, null, null, null, null);

        assertTrue(result.contains("ROUND_TABLE"));
        assertTrue(result.contains("2 members"));

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        var config = captor.getValue();
        assertEquals("Panel", config.getName());
        assertEquals(AgentGroupConfiguration.DiscussionStyle.ROUND_TABLE, config.getStyle());
        assertEquals(2, config.getMembers().size());
        assertEquals("Alice", config.getMembers().get(0).displayName());
        assertEquals("Bob", config.getMembers().get(1).displayName());
        assertEquals(2, config.getMaxRounds());
    }

    @Test
    void createGroup_peerReviewStyle() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/id?version=1")).build());

        String result = tools.create_group("Review", null, "a1,a2,a3", null, null, null, "mod1", "PEER_REVIEW", "1", null, null);

        assertTrue(result.contains("PEER_REVIEW"));

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        var config = captor.getValue();
        assertEquals(AgentGroupConfiguration.DiscussionStyle.PEER_REVIEW, config.getStyle());
        assertEquals("mod1", config.getModeratorAgentId());
        assertEquals(1, config.getMaxRounds());
    }

    @Test
    void createGroup_withMemberRoles() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/id?version=1")).build());

        tools.create_group("DA Panel", null, "a1,a2,a3", "Optimist,Pragmatist,Skeptic", "PARTICIPANT,PARTICIPANT,DEVIL_ADVOCATE", null, "mod1",
                "DEVIL_ADVOCATE", null, null, null);

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        var members = captor.getValue().getMembers();
        assertNull(members.get(0).role()); // PARTICIPANT → null (default)
        assertNull(members.get(1).role());
        assertEquals("DEVIL_ADVOCATE", members.get(2).role());
    }

    @Test
    void createGroup_invalidStyle_fallsBackToRoundTable() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/id")).build());

        tools.create_group("Test", null, "a1", null, null, null, null, "INVALID", null, null, null);

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        assertEquals(AgentGroupConfiguration.DiscussionStyle.ROUND_TABLE, captor.getValue().getStyle());
    }

    @Test
    void createGroup_handlesException() {
        when(groupStore.createGroup(any())).thenThrow(new RuntimeException("Insert failed"));

        String result = tools.create_group("Test", null, "a1", null, null, null, null, null, null, null, null);

        assertTrue(result.contains("error"));
    }

    @Test
    void createGroup_withGroupMembers() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/id?version=1")).build());

        tools.create_group("Meta Panel", null, "g1,g2", "Team A,Team B", null, "GROUP,GROUP", "mod1", "ROUND_TABLE", null, null, null);

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        var members = captor.getValue().getMembers();
        assertEquals(AgentGroupConfiguration.MemberType.GROUP, members.get(0).memberType());
        assertEquals(AgentGroupConfiguration.MemberType.GROUP, members.get(1).memberType());
    }

    @Test
    void describeDiscussionStyles_mentionsNestedGroups() {
        String result = tools.describe_discussion_styles();
        assertTrue(result.contains("Nested Groups"));
        assertTrue(result.contains("GROUP"));
    }

    // --- update_group ---

    @Test
    void updateGroup_success() throws Exception {
        when(jsonSerialization.deserialize(anyString(), eq(AgentGroupConfiguration.class))).thenReturn(new AgentGroupConfiguration());
        when(groupStore.updateGroup(any(), anyInt(), any())).thenReturn(Response.ok().build());

        String result = tools.update_group("g1", "1", "{}");

        assertEquals("Updated group g1", result);
        verify(groupStore).updateGroup(eq("g1"), eq(1), any());
    }

    // --- delete_group ---

    @Test
    void deleteGroup_success() {
        when(groupStore.deleteGroup("g1", 1, false)).thenReturn(Response.ok().build());

        String result = tools.delete_group("g1", "1");

        assertEquals("Deleted group g1", result);
    }

    // --- discuss_with_group ---

    @Test
    void discussWithGroup_success() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        when(groupConversationService.discuss("g1", "What?", "user1", 0)).thenReturn(gc);
        when(jsonSerialization.serialize(gc)).thenReturn("{\"id\":\"gc1\"}");

        String result = tools.discuss_with_group("g1", "What?", "user1");

        assertEquals("{\"id\":\"gc1\"}", result);
        verify(groupConversationService).discuss("g1", "What?", "user1", 0);
    }

    @Test
    void discussWithGroup_defaultsToMcpClient() throws Exception {
        GroupConversation gc = new GroupConversation();
        when(groupConversationService.discuss(any(), any(), any(), anyInt())).thenReturn(gc);

        tools.discuss_with_group("g1", "Q?", null);

        verify(groupConversationService).discuss("g1", "Q?", "mcp-client", 0);
    }

    @Test
    void discussWithGroup_handlesException() throws Exception {
        when(groupConversationService.discuss(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("Failed"));

        String result = tools.discuss_with_group("g1", "Q?", null);

        assertTrue(result.contains("error"));
    }

    // --- read_group_conversation ---

    @Test
    void readGroupConversation_success() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        when(jsonSerialization.serialize(gc)).thenReturn("{\"id\":\"gc1\"}");

        String result = tools.read_group_conversation("gc1");

        assertEquals("{\"id\":\"gc1\"}", result);
    }

    @Test
    void readGroupConversation_handlesException() throws Exception {
        when(groupConversationService.readGroupConversation(any())).thenThrow(new RuntimeException("Not found"));

        String result = tools.read_group_conversation("gc1");

        assertTrue(result.contains("error"));
    }

    // --- list_group_conversations ---

    @Test
    void listGroupConversations_success() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        when(groupConversationService.listGroupConversations("g1", 0, 20)).thenReturn(List.of(gc));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"id\":\"gc1\"}]");

        String result = tools.list_group_conversations("g1", null, null);

        assertNotNull(result);
        verify(groupConversationService).listGroupConversations("g1", 0, 20);
    }

    @Test
    void listGroupConversations_handlesException() throws Exception {
        when(groupConversationService.listGroupConversations(any(), anyInt(), anyInt())).thenThrow(new RuntimeException("DB error"));

        String result = tools.list_group_conversations("g1", null, null);

        assertTrue(result.contains("error"));
    }

    // --- start_group_discussion (async) ---

    @Test
    void startGroupDiscussion_returnsIdAndState() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc-async-1");
        gc.setState(GroupConversation.GroupConversationState.IN_PROGRESS);
        when(groupConversationService.startAndDiscussAsync("g1", "Build it", "user1", null)).thenReturn(gc);
        when(jsonSerialization.serialize(any(java.util.Map.class))).thenReturn(
                "{\"groupConversationId\":\"gc-async-1\",\"state\":\"IN_PROGRESS\",\"message\":\"Discussion started.\"}");

        String result = tools.start_group_discussion("g1", "Build it", "user1");

        assertTrue(result.contains("gc-async-1"), "Should contain conversation ID");
        assertTrue(result.contains("IN_PROGRESS"), "Should indicate in-progress state");
        verify(groupConversationService).startAndDiscussAsync("g1", "Build it", "user1", null);

        // Verify the Map passed to serialize contains the right keys
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(jsonSerialization).serialize(captor.capture());
        var map = captor.getValue();
        assertEquals("gc-async-1", map.get("groupConversationId"));
        assertEquals("IN_PROGRESS", map.get("state"));
        assertNotNull(map.get("message"), "Should include polling instructions");
    }

    @Test
    void startGroupDiscussion_defaultsToMcpClient() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc-async-2");
        gc.setState(GroupConversation.GroupConversationState.IN_PROGRESS);
        when(groupConversationService.startAndDiscussAsync(any(), any(), any(), any())).thenReturn(gc);

        tools.start_group_discussion("g1", "Q?", null);

        verify(groupConversationService).startAndDiscussAsync("g1", "Q?", "mcp-client", null);
    }

    @Test
    void startGroupDiscussion_handlesBlankUserId() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc-async-3");
        gc.setState(GroupConversation.GroupConversationState.IN_PROGRESS);
        when(groupConversationService.startAndDiscussAsync(any(), any(), any(), any())).thenReturn(gc);

        tools.start_group_discussion("g1", "Q?", "  ");

        verify(groupConversationService).startAndDiscussAsync("g1", "Q?", "mcp-client", null);
    }

    @Test
    void startGroupDiscussion_handlesException() throws Exception {
        when(groupConversationService.startAndDiscussAsync(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Group not found"));

        String result = tools.start_group_discussion("g1", "Q?", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Group not found"));
    }

    // --- delete_group_conversation ---

    /**
     * delete/followup/continue/close now load the conversation first to enforce the
     * owner check (MCP parity with REST), so the read must be stubbed.
     */
    private void stubConversation(String id) throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId(id);
        when(groupConversationService.readGroupConversation(id)).thenReturn(gc);
    }

    @Test
    void deleteGroupConversation_success() throws Exception {
        stubConversation("gc-del-1");

        tools.delete_group_conversation("gc-del-1");

        verify(groupConversationService).deleteGroupConversation("gc-del-1");
    }

    @Test
    void deleteGroupConversation_returnsConfirmation() throws Exception {
        stubConversation("gc-del-1");

        String result = tools.delete_group_conversation("gc-del-1");

        assertEquals("Deleted group conversation gc-del-1", result);
    }

    @Test
    void deleteGroupConversation_handlesException() throws Exception {
        stubConversation("gc-bad");
        doThrow(new RuntimeException("Not found")).when(groupConversationService).deleteGroupConversation("gc-bad");

        String result = tools.delete_group_conversation("gc-bad");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Not found"));
    }

    // --- @Blocking annotation ---

    @Test
    void discussWithGroup_hasBlockingAnnotation() throws Exception {
        var method = McpGroupTools.class.getMethod("discuss_with_group", String.class, String.class, String.class);
        assertNotNull(method.getAnnotation(io.smallrye.common.annotation.Blocking.class),
                "discuss_with_group must be annotated with @Blocking to avoid blocking the Vert.x event loop");
    }

    @Test
    void startGroupDiscussion_doesNotHaveBlockingAnnotation() throws Exception {
        var method = McpGroupTools.class.getMethod("start_group_discussion", String.class, String.class, String.class);
        assertNull(method.getAnnotation(io.smallrye.common.annotation.Blocking.class),
                "start_group_discussion is async and should NOT have @Blocking");
    }

    // --- ownership enforcement (MCP must match the REST surface) ---

    /**
     * Builds the tools with auth ON, acting as {@code callerId} with the given
     * roles.
     */
    private McpGroupTools toolsAsUser(String callerId, String role) {
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        var principal = mock(java.security.Principal.class);
        lenient().when(principal.getName()).thenReturn(callerId);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.hasRole(role)).thenReturn(true);
        return new McpGroupTools(groupStore, groupConversationService, jsonSerialization, identity,
                new OwnershipValidator(true), true);
    }

    /**
     * An admin caller: holds eddi-admin (plus the baseline roles the tools
     * require).
     */
    private McpGroupTools toolsAsAdmin(String callerId) {
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        var principal = mock(java.security.Principal.class);
        lenient().when(principal.getName()).thenReturn(callerId);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.hasRole(anyString())).thenReturn(true);
        return new McpGroupTools(groupStore, groupConversationService, jsonSerialization, identity,
                new OwnershipValidator(true), true);
    }

    @Test
    void admin_mayActOnAnotherUsersConversation() throws Exception {
        GroupConversation gc = ownedBy("alice");
        when(groupConversationService.followUpWithMember("gc1", "Analyst", "why?")).thenReturn(gc);
        when(jsonSerialization.serialize(gc)).thenReturn("{\"id\":\"gc1\"}");

        // The other half of requireOwnerOrAdmin: an admin is NOT the owner but must
        // pass.
        String result = toolsAsAdmin("root").followup_with_member("gc1", "Analyst", "why?");

        assertFalse(result.contains("Access denied"));
        verify(groupConversationService).followUpWithMember("gc1", "Analyst", "why?");
    }

    @Test
    void admin_seesAllConversationsInTheGroupListing() throws Exception {
        var mine = new GroupConversation();
        mine.setId("gc-mine");
        mine.setUserId("bob");
        var theirs = new GroupConversation();
        theirs.setId("gc-theirs");
        theirs.setUserId("alice");
        when(groupConversationService.listGroupConversations("g1", 0, 20)).thenReturn(List.of(mine, theirs));

        toolsAsAdmin("root").list_group_conversations("g1", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupConversation>> captor = ArgumentCaptor.forClass(List.class);
        verify(jsonSerialization).serialize(captor.capture());
        assertEquals(2, captor.getValue().size(), "the owner filter must exempt admins");
    }

    private GroupConversation ownedBy(String userId) throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        gc.setUserId(userId);
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        return gc;
    }

    @Test
    void followupWithMember_deniedForNonOwner() throws Exception {
        ownedBy("alice");

        String result = toolsAsUser("bob", "eddi-viewer").followup_with_member("gc1", "Analyst", "why?");

        assertTrue(result.contains("Access denied"), "a non-owner must not follow up on someone else's conversation");
        verify(groupConversationService, never()).followUpWithMember(any(), any(), any());
    }

    @Test
    void continueGroupDiscussion_deniedForNonOwner() throws Exception {
        ownedBy("alice");

        String result = toolsAsUser("bob", "eddi-viewer").continue_group_discussion("gc1", "next?");

        assertTrue(result.contains("Access denied"), "a non-owner must not continue someone else's conversation");
        verify(groupConversationService, never()).continueDiscussion(any(), any(), any());
    }

    @Test
    void closeGroupConversation_deniedForNonOwner() throws Exception {
        ownedBy("alice");

        String result = toolsAsUser("bob", "eddi-editor").close_group_conversation("gc1");

        assertTrue(result.contains("Access denied"), "a non-owner must not close someone else's conversation");
        verify(groupConversationService, never()).closeGroupConversation(any());
    }

    @Test
    void readGroupConversation_deniedForNonOwner() throws Exception {
        ownedBy("alice");

        String result = toolsAsUser("bob", "eddi-viewer").read_group_conversation("gc1");

        assertTrue(result.contains("Access denied"), "a non-owner must not read someone else's transcript");
    }

    @Test
    void deleteGroupConversation_deniedForNonOwner() throws Exception {
        ownedBy("alice");

        String result = toolsAsUser("bob", "eddi-editor").delete_group_conversation("gc1");

        assertTrue(result.contains("Access denied"), "a non-owner must not delete someone else's conversation");
        verify(groupConversationService, never()).deleteGroupConversation(any());
    }

    @Test
    void followupWithMember_allowedForOwner() throws Exception {
        GroupConversation gc = ownedBy("alice");
        when(groupConversationService.followUpWithMember("gc1", "Analyst", "why?")).thenReturn(gc);
        when(jsonSerialization.serialize(gc)).thenReturn("{\"id\":\"gc1\"}");

        String result = toolsAsUser("alice", "eddi-viewer").followup_with_member("gc1", "Analyst", "why?");

        assertEquals("{\"id\":\"gc1\"}", result);
        verify(groupConversationService).followUpWithMember("gc1", "Analyst", "why?");
    }

    // --- owner resolution on creation (the gate must not lock out the creator) ---

    @Test
    void startGroupDiscussion_recordsTheCallerAsOwner_notMcpClient() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc1");
        when(groupConversationService.startAndDiscussAsync(eq("g1"), eq("Q?"), eq("alice"), isNull()))
                .thenReturn(gc);

        toolsAsUser("alice", "eddi-viewer").start_group_discussion("g1", "Q?", null);

        // If the conversation were owned by the literal "mcp-client", the ownership
        // gate
        // would then deny the creator on every follow-up read/continue/close.
        verify(groupConversationService).startAndDiscussAsync("g1", "Q?", "alice", null);
    }

    @Test
    void startGroupDiscussion_cannotCreateAConversationOwnedByAnotherUser() throws Exception {
        String result = toolsAsUser("bob", "eddi-viewer").start_group_discussion("g1", "Q?", "alice");

        assertTrue(result.contains("Access denied"), "impersonating another owner must be rejected");
        verify(groupConversationService, never()).startAndDiscussAsync(any(), any(), any(), any());
    }

    @Test
    void discussWithGroup_recordsTheCallerAsOwner() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc1");
        when(groupConversationService.discuss("g1", "Q?", "alice", 0)).thenReturn(gc);

        toolsAsUser("alice", "eddi-viewer").discuss_with_group("g1", "Q?", null);

        verify(groupConversationService).discuss("g1", "Q?", "alice", 0);
    }

    // --- listing must be owner-filtered, else the per-conversation gate is
    // pointless ---

    @Test
    @SuppressWarnings("unchecked")
    void listGroupConversations_filtersToTheCallersOwnConversations() throws Exception {
        var mine = new GroupConversation();
        mine.setId("gc-mine");
        mine.setUserId("bob");
        var theirs = new GroupConversation();
        theirs.setId("gc-theirs");
        theirs.setUserId("alice");
        when(groupConversationService.listGroupConversations("g1", 0, 20)).thenReturn(List.of(mine, theirs));

        toolsAsUser("bob", "eddi-viewer").list_group_conversations("g1", null, null);

        ArgumentCaptor<List<GroupConversation>> captor = ArgumentCaptor.forClass(List.class);
        verify(jsonSerialization).serialize(captor.capture());
        assertEquals(1, captor.getValue().size(), "a non-owner must not see another user's transcript via list");
        assertEquals("gc-mine", captor.getValue().get(0).getId());
    }
}
