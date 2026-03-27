package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
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
        tools = new McpGroupTools(groupStore, groupConversationService, jsonSerialization, mockIdentity, false);
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

        String result = tools.create_group("Panel", "desc", "a1,a2", "Alice,Bob", null, null, null, null, null);

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

        String result = tools.create_group("Review", null, "a1,a2,a3", null, null, null, "mod1", "PEER_REVIEW", "1");

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
                "DEVIL_ADVOCATE", null);

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

        tools.create_group("Test", null, "a1", null, null, null, null, "INVALID", null);

        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());

        assertEquals(AgentGroupConfiguration.DiscussionStyle.ROUND_TABLE, captor.getValue().getStyle());
    }

    @Test
    void createGroup_handlesException() {
        when(groupStore.createGroup(any())).thenThrow(new RuntimeException("Insert failed"));

        String result = tools.create_group("Test", null, "a1", null, null, null, null, null, null);

        assertTrue(result.contains("error"));
    }

    @Test
    void createGroup_withGroupMembers() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(Response.created(URI.create("/groupstore/groups/id?version=1")).build());

        tools.create_group("Meta Panel", null, "g1,g2", "Team A,Team B", null, "GROUP,GROUP", "mod1", "ROUND_TABLE", null);

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
}
