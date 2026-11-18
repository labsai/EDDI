/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.catalog;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentAppearance;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.ConversationControls;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IRestAgentProfile.AppearanceProfile;
import ai.labs.eddi.engine.api.IRestAgentProfile.ControlsProfile;
import ai.labs.eddi.engine.api.OperatorRevisionCheck;
import ai.labs.eddi.engine.catalog.IRestCatalog.CatalogAgent;
import ai.labs.eddi.engine.catalog.IRestCatalog.CatalogGroup;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The catalog shows an entry only when it is deployed (agents), usable by the
 * caller, and listed. These tests run the real {@link ResourceAccessGuard}, so
 * "usable" is decided by the same code that guards starting a conversation.
 */
class CatalogServiceTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    // Group ids must look like store ids: the descriptor's resource URI is parsed
    // with RestUtilities.extractResourceId, which rejects anything else.
    private static final String G1 = "aaaaaaaaaaaaaaaaaaaaaaa1";
    private static final String G2 = "aaaaaaaaaaaaaaaaaaaaaaa2";

    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IAgentGroupStore groupStore;
    private IDocumentDescriptorStore descriptorStore;
    private IGlobalVariableStore variableStore;
    private ConversationReviewPolicy reviewPolicy;
    private ConversationRatingPolicy ratingPolicy;
    private final List<IAgent> deployed = new ArrayList<>();
    private final List<DocumentDescriptor> groupDescriptors = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        groupStore = mock(IAgentGroupStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        variableStore = mock(IGlobalVariableStore.class);
        reviewPolicy = mock(ConversationReviewPolicy.class);
        ratingPolicy = mock(ConversationRatingPolicy.class);
        deployed.clear();
        groupDescriptors.clear();
        when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(deployed);
        lenient().when(reviewPolicy.noticeFor(anyString(), any())).thenReturn(Optional.empty());
        lenient().when(ratingPolicy.ratingConfigOf(anyString(), any())).thenReturn(Optional.empty());
        lenient().when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenAnswer(invocation -> (int) invocation.getArgument(2) == 0 ? List.copyOf(groupDescriptors) : List.of());
    }

    // ==================== fixtures ====================

    private static WorkspaceSettings workspaces(boolean enabled) {
        var settings = new WorkspaceSettings(enabled, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty());
        return settings;
    }

    private static SecurityIdentity identity(String principal, String... roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        if (principal == null) {
            lenient().when(identity.isAnonymous()).thenReturn(true);
            return identity;
        }
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn(principal);
        lenient().when(identity.getPrincipal()).thenReturn(p);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    private CatalogService service(SecurityIdentity identity, WorkspaceSettings settings) {
        var guard = new ResourceAccessGuard(identity, new OwnershipValidator(settings.isStampingOwnership()),
                new SpaceContext(identity, settings), settings, descriptorStore);
        return new CatalogService(agentFactory, agentStore, groupStore, descriptorStore, guard, settings, reviewPolicy, ratingPolicy,
                variableStore, new ObjectMapper());
    }

    private static DocumentDescriptor descriptor(String name, String owner, ResourceVisibility visibility, URI resource) {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setName(name);
        d.setDescription(name + " description");
        d.setOwnerId(owner);
        d.setSpaceId(Subjects.personalSpace(owner));
        d.setVisibility(visibility.wireName());
        d.setResource(resource);
        return DescriptorAccess.rebuildIndex(d);
    }

    /** A READY production agent owned by {@code owner}. */
    private void agent(String id, String name, String owner, ResourceVisibility visibility, Boolean listed) throws Exception {
        agent(id, name, owner, visibility, listed, Deployment.Status.READY);
    }

    private void agent(String id, String name, String owner, ResourceVisibility visibility, Boolean listed, Deployment.Status status)
            throws Exception {
        IAgent agent = mock(IAgent.class);
        lenient().when(agent.getAgentId()).thenReturn(id);
        lenient().when(agent.getAgentVersion()).thenReturn(3);
        lenient().when(agent.getDeploymentStatus()).thenReturn(status);
        deployed.add(agent);

        var config = new AgentConfiguration();
        config.setListed(listed);
        lenient().when(agentStore.read(id, 3)).thenReturn(config);
        lenient().when(descriptorStore.readCurrentDescriptor(id))
                .thenReturn(descriptor(name, owner, visibility, URI.create("eddi://ai.labs.agent/agentstore/agents/" + id + "?version=3")));
    }

    private void group(String id, int version, String name, String owner, ResourceVisibility visibility, Boolean listed) throws Exception {
        groupDescriptors.add(descriptor(name, owner, visibility,
                URI.create("eddi://ai.labs.group/groupstore/groups/" + id + "?version=" + version)));
        var config = new AgentGroupConfiguration();
        config.setName(name);
        config.setDescription(name + " config description");
        config.setStyle(DiscussionStyle.ROUND_TABLE);
        config.setListed(listed);
        config.setMembers(List.of(new GroupMember("member-1", "Analyst", 1, null, MemberType.AGENT)));
        lenient().when(groupStore.read(id, version)).thenReturn(config);
    }

    private void operatorIs(String agentId) {
        when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                .thenReturn(new GlobalVariable(OperatorRevisionCheck.OPERATOR_VARIABLE,
                        "{\"enabled\":true,\"agentId\":\"" + agentId + "\"}"));
    }

    private static List<String> agentIds(List<CatalogAgent> agents) {
        return agents.stream().map(CatalogAgent::agentId).toList();
    }

    private static List<String> groupIds(List<CatalogGroup> groups) {
        return groups.stream().map(CatalogGroup::groupId).toList();
    }

    // ==================== agents ====================

    @Nested
    @DisplayName("agents, workspaces off")
    class AgentsWithoutWorkspaces {

        private final WorkspaceSettings settings = workspaces(false);

        @Test
        @DisplayName("an upgrade lists nothing: unlisted agents stay out")
        void unlistedHidden() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, null);
            agent("a2", "Billing", ALICE, ResourceVisibility.space, false);

            assertTrue(service(identity(BOB, "eddi-user"), settings).listAgents().isEmpty());
        }

        @Test
        @DisplayName("a listed agent appears with its profile fields and the caller's level")
        void listedShownWithProfile() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            when(reviewPolicy.noticeFor("a1", 3)).thenReturn(Optional.of("Conversations may be reviewed"));

            List<CatalogAgent> agents = service(identity(BOB, "eddi-user"), settings).listAgents();

            assertEquals(1, agents.size());
            CatalogAgent entry = agents.getFirst();
            assertEquals("a1", entry.agentId());
            assertEquals("Support", entry.name());
            assertEquals("Support description", entry.description());
            assertEquals("Conversations may be reviewed", entry.reviewNotice());
            assertNull(entry.rating());
            // Workspaces off: everyone may do everything, as before the feature.
            assertEquals("OWN", entry.callerLevel());
            // No appearance set: none served, and the controls are the defaults.
            assertNull(entry.appearance());
            assertEquals(ControlsProfile.of(null), entry.conversationControls());
            assertFalse(entry.conversationControls().rawHtml());
        }

        @Test
        @DisplayName("a listed agent carries the deployed version's appearance and controls, as the profile serves them")
        void listedShownWithAppearance() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            var appearance = new AgentAppearance();
            appearance.setDisplayName("Ada");
            appearance.setAvatar("https://cdn.example.com/ada.png");
            appearance.setAccentColor("#0a7");
            appearance.setStarters(List.of("Track my order", "Start a return"));
            var controls = new ConversationControls();
            controls.setUndo(false);
            controls.setStreaming(false);
            controls.setRawHtml(true);
            AgentConfiguration config = agentStore.read("a1", 3);
            config.setAppearance(appearance);
            config.setConversationControls(controls);

            CatalogAgent entry = service(identity(BOB, "eddi-user"), settings).listAgents().getFirst();

            assertEquals(new AppearanceProfile("Ada", "https://cdn.example.com/ada.png", "#0a7",
                    List.of("Track my order", "Start a return")), entry.appearance());
            assertEquals(ControlsProfile.of(controls), entry.conversationControls());
            assertFalse(entry.conversationControls().undo());
            assertTrue(entry.conversationControls().redo());
            assertFalse(entry.conversationControls().streaming());
            assertTrue(entry.conversationControls().rawHtml());
        }

        @Test
        @DisplayName("an entry built without controls still reports the defaults, never null")
        void controlsNeverNull() {
            var entry = new CatalogAgent("a1", "Support", null, null, null, "USE");

            assertNull(entry.appearance());
            assertEquals(ControlsProfile.of(null), entry.conversationControls());
        }

        @Test
        @DisplayName("published visibility does not list an agent while workspaces are off")
        void visibilityIgnoredWithoutWorkspaces() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.published, null);

            assertTrue(service(identity(BOB, "eddi-user"), settings).listAgents().isEmpty());
        }

        @Test
        @DisplayName("an agent that is not READY is not offered")
        void notReadyHidden() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true, Deployment.Status.IN_PROGRESS);

            assertTrue(service(identity(BOB, "eddi-user"), settings).listAgents().isEmpty());
        }

        @Test
        @DisplayName("the Platform Operator never appears unless explicitly listed")
        void operatorHidden() throws Exception {
            agent("op", "Platform Operator", ALICE, ResourceVisibility.space, false);
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            operatorIs("op");

            assertEquals(List.of("a1"), agentIds(service(identity(BOB, "eddi-user"), settings).listAgents()));
        }

        @Test
        @DisplayName("entries are ordered by name, case-insensitively")
        void orderedByName() throws Exception {
            agent("a1", "zeta", ALICE, ResourceVisibility.space, true);
            agent("a2", "Alpha", ALICE, ResourceVisibility.space, true);

            assertEquals(List.of("a2", "a1"), agentIds(service(identity(BOB, "eddi-user"), settings).listAgents()));
        }

        @Test
        @DisplayName("an agent whose configuration cannot be read is left out, not fatal")
        void unreadableConfigSkipped() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            agent("a2", "Broken", ALICE, ResourceVisibility.space, true);
            when(agentStore.read("a2", 3)).thenThrow(new ResourceNotFoundException("gone"));

            assertEquals(List.of("a1"), agentIds(service(identity(BOB, "eddi-user"), settings).listAgents()));
        }
    }

    @Nested
    @DisplayName("agents, workspaces enforced")
    class AgentsWithWorkspaces {

        private final WorkspaceSettings settings = workspaces(true);

        @Test
        @DisplayName("listed but not usable by the caller: hidden")
        void listedWithoutAccessHidden() throws Exception {
            // Alice's personal agent, flagged as listed — Bob may not use it.
            agent("a1", "Alice's helper", ALICE, ResourceVisibility.space, true);

            assertTrue(service(identity(BOB, "eddi-user"), settings).listAgents().isEmpty());
            // Alice herself sees it, as its owner.
            List<CatalogAgent> own = service(identity(ALICE, "eddi-user"), settings).listAgents();
            assertEquals(List.of("a1"), agentIds(own));
            assertEquals("OWN", own.getFirst().callerLevel());
        }

        @Test
        @DisplayName("published and internal visibility count as listed; space does not")
        void visibilityCountsAsListed() throws Exception {
            agent("pub", "Published", ALICE, ResourceVisibility.published, null);
            agent("int", "Internal", ALICE, ResourceVisibility.internal, false);
            agent("spc", "Space", ALICE, ResourceVisibility.space, null);

            List<CatalogAgent> agents = service(identity(BOB, "eddi-user"), settings).listAgents();

            assertEquals(List.of("int", "pub"), agentIds(agents));
            assertEquals("USE", agents.get(0).callerLevel());
            assertEquals("VIEW", agents.get(1).callerLevel());
        }

        @Test
        @DisplayName("the operator is not listed by visibility, only by its explicit flag")
        void operatorNeedsExplicitFlag() throws Exception {
            agent("op", "Platform Operator", ALICE, ResourceVisibility.published, null);
            operatorIs("op");
            assertTrue(service(identity(BOB, "eddi-user"), settings).listAgents().isEmpty());

            when(agentStore.read("op", 3)).thenReturn(listedConfig());
            assertEquals(List.of("op"), agentIds(service(identity(BOB, "eddi-user"), settings).listAgents()));
        }

        @Test
        @DisplayName("an anonymous caller sees published agents only, as /v1/models does")
        void anonymousSeesPublishedOnly() throws Exception {
            agent("pub", "Published", ALICE, ResourceVisibility.published, null);
            agent("int", "Internal", ALICE, ResourceVisibility.internal, true);
            agent("spc", "Space", ALICE, ResourceVisibility.space, true);

            assertEquals(List.of("pub"), agentIds(service(identity(null), settings).listAgents()));
        }

        @Test
        @DisplayName("an administrator still only sees listed agents")
        void adminSeesListedOnly() throws Exception {
            agent("a1", "Listed", ALICE, ResourceVisibility.space, true);
            agent("a2", "Unlisted", ALICE, ResourceVisibility.space, null);

            assertEquals(List.of("a1"), agentIds(service(identity("root", "eddi-admin"), settings).listAgents()));
        }

        private AgentConfiguration listedConfig() {
            var config = new AgentConfiguration();
            config.setListed(true);
            return config;
        }
    }

    // ==================== groups ====================

    @Nested
    @DisplayName("groups")
    class Groups {

        @Test
        @DisplayName("unlisted groups stay out; listed ones carry members and style")
        void listedGroupShown() throws Exception {
            group(G1, 2, "Research pod", ALICE, ResourceVisibility.space, true);
            group(G2, 1, "Drafts", ALICE, ResourceVisibility.space, null);

            List<CatalogGroup> groups = service(identity(BOB, "eddi-user"), workspaces(false)).listGroups();

            assertEquals(List.of(G1), groupIds(groups));
            CatalogGroup entry = groups.getFirst();
            assertEquals(2, entry.version());
            assertEquals("Research pod", entry.name());
            assertEquals("Research pod config description", entry.description());
            assertEquals("ROUND_TABLE", entry.style());
            assertEquals(1, entry.members().size());
            assertEquals("Analyst", entry.members().getFirst().displayName());
            assertEquals("AGENT", entry.members().getFirst().memberType());
        }

        @Test
        @DisplayName("listed but not usable by the caller: hidden")
        void listedWithoutAccessHidden() throws Exception {
            group(G1, 1, "Alice's team", ALICE, ResourceVisibility.space, true);

            assertTrue(service(identity(BOB, "eddi-user"), workspaces(true)).listGroups().isEmpty());
        }

        @Test
        @DisplayName("with workspaces enforced, internal visibility lists a group for signed-in callers only")
        void internalGroup() throws Exception {
            group(G1, 1, "Company team", ALICE, ResourceVisibility.internal, null);

            List<CatalogGroup> groups = service(identity(BOB, "eddi-user"), workspaces(true)).listGroups();
            assertEquals(List.of(G1), groupIds(groups));
            assertEquals("USE", groups.getFirst().callerLevel());

            assertTrue(service(identity(null), workspaces(true)).listGroups().isEmpty());
        }

        @Test
        @DisplayName("only the newest version of a group is offered")
        void newestVersionWins() throws Exception {
            group(G1, 1, "Old name", ALICE, ResourceVisibility.space, true);
            group(G1, 3, "New name", ALICE, ResourceVisibility.space, true);

            List<CatalogGroup> groups = service(identity(BOB, "eddi-user"), workspaces(false)).listGroups();

            assertEquals(1, groups.size());
            assertEquals(3, groups.getFirst().version());
            assertEquals("New name", groups.getFirst().name());
        }

        @Test
        @DisplayName("a store failure yields an empty catalog rather than an error")
        void storeFailureIsEmpty() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), eq(0), anyInt(), anyBoolean(), any()))
                    .thenThrow(new RuntimeException("db down"));

            assertTrue(service(identity(BOB, "eddi-user"), workspaces(false)).listGroups().isEmpty());
        }
    }

    // ==================== degraded inputs ====================

    @Nested
    @DisplayName("degraded inputs never break the catalog")
    class DegradedInputs {

        private final WorkspaceSettings off = workspaces(false);

        @Test
        @DisplayName("no deployed agents, or a factory that fails, give an empty agent list")
        void noDeployedAgents() throws Exception {
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(null);
            assertTrue(service(identity(BOB, "eddi-user"), off).listAgents().isEmpty());

            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenThrow(new IllegalStateException("down"));
            assertTrue(service(identity(BOB, "eddi-user"), off).listAgents().isEmpty());
        }

        @Test
        @DisplayName("a null entry and a second deployment of the same agent are skipped")
        void nullAndDuplicateAgentsSkipped() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            deployed.add(null);
            deployed.add(deployed.getFirst());

            assertEquals(List.of("a1"), agentIds(service(identity(BOB, "eddi-user"), off).listAgents()));
        }

        @Test
        @DisplayName("an agent without a descriptor is listed by its flag, sorted by id, with no name")
        void agentWithoutDescriptor() throws Exception {
            agent("b-agent", "Named", ALICE, ResourceVisibility.space, true);
            agent("a-agent", "ignored", ALICE, ResourceVisibility.space, true);
            when(descriptorStore.readCurrentDescriptor("a-agent")).thenThrow(new ResourceNotFoundException("none"));

            List<CatalogAgent> agents = service(identity(BOB, "eddi-user"), off).listAgents();

            assertEquals(List.of("a-agent", "b-agent"), agentIds(agents));
            assertNull(agents.getFirst().name());
            assertNull(agents.getFirst().description());
        }

        @Test
        @DisplayName("an agent whose configuration reads as null is not listed")
        void nullAgentConfigHidden() throws Exception {
            agent("a1", "Support", ALICE, ResourceVisibility.space, true);
            when(agentStore.read("a1", 3)).thenReturn(null);

            assertTrue(service(identity(BOB, "eddi-user"), off).listAgents().isEmpty());
        }

        @Test
        @DisplayName("an unreadable operator variable means no operator, so nothing is excluded")
        void operatorVariableUnreadable() throws Exception {
            agent("op", "Operator", ALICE, ResourceVisibility.space, true);
            CatalogService catalog = service(identity(BOB, "eddi-user"), off);

            when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE)).thenReturn(null);
            assertNull(catalog.operatorAgentId());

            when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                    .thenReturn(new GlobalVariable(OperatorRevisionCheck.OPERATOR_VARIABLE, " "));
            assertNull(catalog.operatorAgentId());

            when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                    .thenReturn(new GlobalVariable(OperatorRevisionCheck.OPERATOR_VARIABLE, "{not json"));
            assertNull(catalog.operatorAgentId());

            when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                    .thenReturn(new GlobalVariable(OperatorRevisionCheck.OPERATOR_VARIABLE, "{\"agentId\":42}"));
            assertNull(catalog.operatorAgentId());

            when(variableStore.get(GlobalVariable.DEFAULT_TENANT, OperatorRevisionCheck.OPERATOR_VARIABLE))
                    .thenReturn(new GlobalVariable(OperatorRevisionCheck.OPERATOR_VARIABLE, "{\"agentId\":\"\"}"));
            assertNull(catalog.operatorAgentId());

            assertEquals(List.of("op"), agentIds(catalog.listAgents()));
        }

        @Test
        @DisplayName("isListed: no descriptor under enforcement, and a visibility that does not list")
        void isListedEdges() {
            CatalogService enforcing = service(identity(BOB, "eddi-user"), workspaces(true));

            assertTrue(!enforcing.isListed(null, null, false));
            assertTrue(!enforcing.isListed(false,
                    descriptor("x", ALICE, ResourceVisibility.space, URI.create("eddi://ai.labs.agent/agentstore/agents/x?version=1")),
                    false));
            assertTrue(enforcing.isListed(true, null, true));
        }

        @Test
        @DisplayName("a null page, null or unparsable descriptors are skipped")
        void unusableGroupDescriptorsSkipped() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(null);
            assertTrue(service(identity(BOB, "eddi-user"), off).listGroups().isEmpty());

            group(G1, 1, "Team", ALICE, ResourceVisibility.space, true);
            List<DocumentDescriptor> page = new ArrayList<>();
            page.add(null);
            page.add(descriptor("Broken", ALICE, ResourceVisibility.space, URI.create("eddi://ai.labs.group/groupstore/groups/not-an-id")));
            page.addAll(groupDescriptors);
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                    .thenAnswer(invocation -> (int) invocation.getArgument(2) == 0 ? page : List.of());

            assertEquals(List.of(G1), groupIds(service(identity(BOB, "eddi-user"), off).listGroups()));
        }

        @Test
        @DisplayName("an older version seen after a newer one does not replace it")
        void olderVersionAfterNewerIgnored() throws Exception {
            group(G1, 3, "New name", ALICE, ResourceVisibility.space, true);
            group(G1, 1, "Old name", ALICE, ResourceVisibility.space, true);

            List<CatalogGroup> groups = service(identity(BOB, "eddi-user"), off).listGroups();

            assertEquals(1, groups.size());
            assertEquals(3, groups.getFirst().version());
        }

        @Test
        @DisplayName("a full page asks for the next one")
        void fullPageContinues() throws Exception {
            group(G1, 1, "Team", ALICE, ResourceVisibility.space, true);
            DocumentDescriptor only = groupDescriptors.getFirst();
            List<DocumentDescriptor> fullPage = new ArrayList<>();
            for (int i = 0; i < CatalogService.GROUP_PAGE_SIZE; i++) {
                fullPage.add(only);
            }
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                    .thenAnswer(invocation -> (int) invocation.getArgument(2) == 0 ? fullPage : List.of());

            assertEquals(List.of(G1), groupIds(service(identity(BOB, "eddi-user"), off).listGroups()));
        }

        @Test
        @DisplayName("a group with blank name and description, no style and a null member falls back to its descriptor")
        void sparseGroupFallsBack() throws Exception {
            group(G1, 1, "Descriptor name", ALICE, ResourceVisibility.space, true);
            var config = new AgentGroupConfiguration();
            config.setName(" ");
            config.setDescription("");
            config.setListed(true);
            List<GroupMember> members = new ArrayList<>();
            members.add(null);
            members.add(new GroupMember("m1", "Helper", 1, null, null));
            config.setMembers(members);
            when(groupStore.read(G1, 1)).thenReturn(config);

            CatalogGroup entry = service(identity(BOB, "eddi-user"), off).listGroups().getFirst();

            assertEquals("Descriptor name", entry.name());
            assertEquals("Descriptor name description", entry.description());
            assertNull(entry.style());
            assertEquals(1, entry.members().size());
            // GroupMember defaults a null member type to AGENT.
            assertEquals("AGENT", entry.members().getFirst().memberType());
        }

        @Test
        @DisplayName("a group without members, a null configuration, or a failing read")
        void groupConfigEdges() throws Exception {
            group(G1, 1, "No members", ALICE, ResourceVisibility.space, true);
            var config = new AgentGroupConfiguration();
            config.setName("No members");
            config.setListed(true);
            config.setMembers(null);
            when(groupStore.read(G1, 1)).thenReturn(config);
            assertTrue(service(identity(BOB, "eddi-user"), off).listGroups().getFirst().members().isEmpty());

            when(groupStore.read(G1, 1)).thenReturn(null);
            assertTrue(service(identity(BOB, "eddi-user"), off).listGroups().isEmpty());

            when(groupStore.read(G1, 1)).thenThrow(new ResourceNotFoundException("gone"));
            assertTrue(service(identity(BOB, "eddi-user"), off).listGroups().isEmpty());
        }
    }
}
