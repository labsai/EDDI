/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorCheckpoint;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorMove;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3: every agent a group runs — members, moderator, facilitator — must be one
 * the caller may use, on create, update and duplicate alike.
 */
class RestAgentGroupStoreUseCheckTest {

    private static final String GROUP_ID = "aabbccddeeff001122334455";
    private static final String PRIVATE_AGENT = "private-agent";

    private IAgentGroupStore groupStore;
    private ResourceAccessGuard guard;
    private RestAgentGroupStore sut;

    @BeforeEach
    void setUp() {
        groupStore = mock(IAgentGroupStore.class);
        guard = mock(ResourceAccessGuard.class);
        doThrow(new ForbiddenException("Access denied")).when(guard).requireAgentUseAccess(PRIVATE_AGENT);
        sut = new RestAgentGroupStore(groupStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(IGroupWorkspaceStore.class), mock(IScheduleStore.class), guard);
    }

    private static AgentGroupConfiguration group() {
        var config = new AgentGroupConfiguration();
        config.setName("G");
        config.setMembers(List.of(new GroupMember("a1", "Alice", 1, null)));
        return config;
    }

    private static FacilitatorConfig facilitatedBy(String agentId) {
        return new FacilitatorConfig(true, agentId, List.of(FacilitatorMove.values()), FacilitatorCheckpoint.EACH_PHASE, 5, null);
    }

    @Test
    @DisplayName("a private moderator is refused on create, and nothing is stored")
    void moderatorChecked() throws Exception {
        var config = group();
        config.setModeratorAgentId(PRIVATE_AGENT);

        assertThrows(ForbiddenException.class, () -> sut.createGroup(config));
        verify(groupStore, never()).create(any());
    }

    @Test
    @DisplayName("a private facilitator is refused on update")
    void facilitatorChecked() throws Exception {
        var config = group();
        config.setFacilitator(facilitatedBy(PRIVATE_AGENT));

        assertThrows(ForbiddenException.class, () -> sut.updateGroup(GROUP_ID, 1, config));
        verify(groupStore, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("members, moderator and facilitator the caller may use are all asked about, a HUMAN member is not")
    void everyAgentAskedHumansSkipped() throws Exception {
        var config = group();
        config.setMembers(List.of(new GroupMember("a1", "Alice", 1, null), new GroupMember("carol", "Carol", 2, null, MemberType.HUMAN)));
        config.setModeratorAgentId("mod");
        config.setFacilitator(facilitatedBy("fac"));
        when(groupStore.create(any())).thenReturn(resourceId());

        sut.createGroup(config);

        verify(guard).requireAgentUseAccess("a1");
        verify(guard).requireAgentUseAccess("mod");
        verify(guard).requireAgentUseAccess("fac");
        verify(guard, never()).requireAgentUseAccess("carol");
    }

    @Test
    @DisplayName("duplicating a group re-checks the agents it runs for the caller who will own the copy")
    void duplicateChecked() throws Exception {
        var source = group();
        source.setMembers(List.of(new GroupMember(PRIVATE_AGENT, "Private", 1, null)));
        when(groupStore.read(GROUP_ID, 1)).thenReturn(source);

        assertThrows(ForbiddenException.class, () -> sut.duplicateGroup(GROUP_ID, 1));
        verify(groupStore, never()).create(any());
    }

    private static IResourceStore.IResourceId resourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }
}
