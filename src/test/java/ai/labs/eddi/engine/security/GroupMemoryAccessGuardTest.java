/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 — naming a group in a memory recall admits that group's team-owned
 * lessons, so the caller must be entitled to the group, workspaces on or off.
 */
@DisplayName("GroupMemoryAccessGuard")
class GroupMemoryAccessGuardTest {

    private static final String GROUP = "0a0a0a0a0a0a0a0a0a0a0a0a";

    private IGroupConversationStore groupConversationStore;
    private IAgentGroupStore agentGroupStore;
    private ResourceAccessGuard resourceAccessGuard;
    private WorkspaceSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        groupConversationStore = mock(IGroupConversationStore.class);
        agentGroupStore = mock(IAgentGroupStore.class);
        resourceAccessGuard = mock(ResourceAccessGuard.class);
        settings = mock(WorkspaceSettings.class);
        when(resourceAccessGuard.settings()).thenReturn(settings);
        lenient().when(groupConversationStore.listByGroupId(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(agentGroupStore.getCurrentResourceId(GROUP)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        });
        lenient().when(agentGroupStore.read(GROUP, 1)).thenReturn(new AgentGroupConfiguration());
    }

    private static SecurityIdentity caller(String name, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(name);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    private GroupMemoryAccessGuard guardFor(SecurityIdentity identity, boolean authEnabled) {
        return new GroupMemoryAccessGuard(identity, new OwnershipValidator(authEnabled), resourceAccessGuard, groupConversationStore,
                agentGroupStore);
    }

    @Test
    @DisplayName("workspaces off: a user who takes no part in the group is refused its lessons")
    void outsiderRefused() {
        var guard = guardFor(caller("mallory", "eddi-user"), true);

        assertThrows(ForbiddenException.class, () -> guard.requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("workspaces off: a user who started a discussion of the group is entitled")
    void discussionOwnerEntitled() throws Exception {
        when(groupConversationStore.listByGroupId(GROUP, "alice", 0, 1)).thenReturn(List.of(new GroupConversation()));

        assertDoesNotThrow(() -> guardFor(caller("alice", "eddi-user"), true).requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("workspaces off: a HUMAN member of the group is entitled")
    void humanMemberEntitled() throws Exception {
        var group = new AgentGroupConfiguration();
        group.setMembers(List.of(new GroupMember("bob", "Bob", null, null, MemberType.HUMAN)));
        when(agentGroupStore.read(GROUP, 1)).thenReturn(group);

        assertDoesNotThrow(() -> guardFor(caller("bob", "eddi-user"), true).requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("an AGENT member whose id happens to equal the caller's name is not a membership")
    void agentMemberIsNotAHuman() throws Exception {
        var group = new AgentGroupConfiguration();
        group.setMembers(List.of(new GroupMember("bob", "Bob", null, null, MemberType.AGENT)));
        when(agentGroupStore.read(GROUP, 1)).thenReturn(group);

        assertThrows(ForbiddenException.class, () -> guardFor(caller("bob", "eddi-user"), true).requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("every named group is checked")
    void everyGroupChecked() throws Exception {
        when(groupConversationStore.listByGroupId(GROUP, "alice", 0, 1)).thenReturn(List.of(new GroupConversation()));

        assertThrows(ForbiddenException.class,
                () -> guardFor(caller("alice", "eddi-user"), true).requireEntitledToEach(List.of(GROUP, "other-team-group")));
    }

    @Test
    @DisplayName("a store failure never widens access")
    void storeFailureFailsClosed() throws Exception {
        when(groupConversationStore.listByGroupId(anyString(), anyString(), anyInt(), anyInt())).thenThrow(new ResourceStoreException("down"));
        when(agentGroupStore.read(eq(GROUP), anyInt())).thenThrow(new ResourceStoreException("down"));

        assertThrows(ForbiddenException.class, () -> guardFor(caller("alice", "eddi-user"), true).requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("an administrator, and anyone while authorization is off, is admitted without a lookup")
    void adminAndAuthOffAdmitted() throws Exception {
        assertDoesNotThrow(() -> guardFor(caller("root", "eddi-admin"), true).requireEntitledToEach(List.of(GROUP)));
        assertDoesNotThrow(() -> guardFor(caller("anyone"), false).requireEntitledToEach(List.of(GROUP)));
        verify(groupConversationStore, never()).listByGroupId(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("workspaces on: USE on the group is the rule, as before")
    void workspacesOnUsesTheUseGate() {
        when(settings.isEnforcing()).thenReturn(true);
        doThrow(new ForbiddenException("no")).when(resourceAccessGuard).requireUseAccessToEach(List.of(GROUP), "group");

        assertThrows(ForbiddenException.class, () -> guardFor(caller("alice", "eddi-user"), true).requireEntitledToEach(List.of(GROUP)));
    }

    @Test
    @DisplayName("no groups named: nothing to check")
    void noGroups() {
        assertDoesNotThrow(() -> guardFor(caller("mallory", "eddi-user"), true).requireEntitledToEach(List.of()));
        assertDoesNotThrow(() -> guardFor(caller("mallory", "eddi-user"), true).requireEntitledToEach(null));
    }
}
