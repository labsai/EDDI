/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolver decides what a cascading share actually reaches, so a node it
 * silently drops is a recipient staring at a broken agent — and the sharing
 * tests mock this class, which is precisely how a dropped node stayed
 * invisible. These tests run the real traversal over in-memory stores.
 */
class ConfigGraphResolverTest {

    private static final String GROUP = "cccccccccccccccccccccc";
    private static final String NESTED_GROUP = "dddddddddddddddddddddd";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_2 = "eeeeeeeeeeeeeeeeeeeeee";
    private static final String WORKFLOW = "bbbbbbbbbbbbbbbbbbbbbb";
    private static final String WORKFLOW_2 = "ffffffffffffffffffffff";
    private static final String RULE_SET = "1111111111111111111111";
    private static final String DICTIONARY = "2222222222222222222222";

    private final Map<String, AgentConfiguration> agents = new HashMap<>();
    private final Map<String, WorkflowConfiguration> workflows = new HashMap<>();
    private final Map<String, AgentGroupConfiguration> groups = new HashMap<>();

    private ConfigGraphResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        IAgentStore agentStore = mock(IAgentStore.class);
        IWorkflowStore workflowStore = mock(IWorkflowStore.class);
        IAgentGroupStore groupStore = mock(IAgentGroupStore.class);

        wire(agentStore, agents);
        wire(workflowStore, workflows);
        wire(groupStore, groups);

        resolver = new ConfigGraphResolver(agentStore, workflowStore, groupStore);
    }

    private <T> void wire(Object store, Map<String, T> byId) throws Exception {
        if (store instanceof IAgentStore s) {
            lenient().when(s.getCurrentResourceId(anyString())).thenAnswer(i -> resourceIdOrThrow(byId, i.getArgument(0)));
            lenient().when(s.read(anyString(), anyInt())).thenAnswer(i -> readOrThrow(byId, i.getArgument(0)));
        } else if (store instanceof IWorkflowStore s) {
            lenient().when(s.getCurrentResourceId(anyString())).thenAnswer(i -> resourceIdOrThrow(byId, i.getArgument(0)));
            lenient().when(s.read(anyString(), anyInt())).thenAnswer(i -> readOrThrow(byId, i.getArgument(0)));
        } else if (store instanceof IAgentGroupStore s) {
            lenient().when(s.getCurrentResourceId(anyString())).thenAnswer(i -> resourceIdOrThrow(byId, i.getArgument(0)));
            lenient().when(s.read(anyString(), anyInt())).thenAnswer(i -> readOrThrow(byId, i.getArgument(0)));
        }
    }

    private static <T> IResourceStore.IResourceId resourceIdOrThrow(Map<String, T> byId, String id)
            throws IResourceStore.ResourceNotFoundException {
        if (!byId.containsKey(id)) {
            throw new IResourceStore.ResourceNotFoundException("no " + id);
        }
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    private static <T> T readOrThrow(Map<String, T> byId, String id) throws IResourceStore.ResourceNotFoundException {
        T value = byId.get(id);
        if (value == null) {
            throw new IResourceStore.ResourceNotFoundException("no " + id);
        }
        return value;
    }

    // ---- fixture builders ----

    private void agent(String id, String... workflowIds) {
        var config = new AgentConfiguration();
        List<URI> uris = new ArrayList<>();
        for (String workflowId : workflowIds) {
            uris.add(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1"));
        }
        config.setWorkflows(uris);
        agents.put(id, config);
    }

    private void workflow(String id, String... configIds) {
        var config = new WorkflowConfiguration();
        List<WorkflowConfiguration.WorkflowStep> steps = new ArrayList<>();
        for (String configId : configIds) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.behavior"));
            step.setConfig(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/" + configId + "?version=1"));
            steps.add(step);
        }
        config.setWorkflowSteps(steps);
        workflows.put(id, config);
    }

    private void group(String id, String... memberIds) {
        var config = new AgentGroupConfiguration();
        List<AgentGroupConfiguration.GroupMember> members = new ArrayList<>();
        for (String memberId : memberIds) {
            members.add(new AgentGroupConfiguration.GroupMember(memberId, memberId, null, null, null));
        }
        config.setMembers(members);
        groups.put(id, config);
    }

    // ---- the traversal ----

    @Test
    @DisplayName("an agent reaches its workflows and their configs, excluding itself")
    void agentGraph() {
        agent(AGENT, WORKFLOW);
        workflow(WORKFLOW, RULE_SET, DICTIONARY);

        Set<String> reached = resolver.referencedResourceIds(AGENT);

        assertTrue(reached.containsAll(Set.of(WORKFLOW, RULE_SET, DICTIONARY)), "reached: " + reached);
        assertFalse(reached.contains(AGENT), "the root is the caller's to share; only what it references is returned");
    }

    @Test
    @DisplayName("sharing a group reaches the member agents THEMSELVES, not only their workflows")
    void groupIncludesMemberAgents() {
        group(GROUP, AGENT, AGENT_2);
        agent(AGENT, WORKFLOW);
        agent(AGENT_2, WORKFLOW_2);
        workflow(WORKFLOW, RULE_SET);
        workflow(WORKFLOW_2, DICTIONARY);

        Set<String> reached = resolver.referencedResourceIds(GROUP);

        // The regression this pins down: the seeding recursion and the poll loop
        // shared one visited-set, so every member agent was pre-marked "done" and
        // dropped from the result — recipients of a group share could reach the
        // workflows but not start a conversation with any member.
        assertTrue(reached.contains(AGENT), "member agent missing from the share: " + reached);
        assertTrue(reached.contains(AGENT_2), "member agent missing from the share: " + reached);
        assertTrue(reached.containsAll(Set.of(WORKFLOW, WORKFLOW_2, RULE_SET, DICTIONARY)), "reached: " + reached);
    }

    @Test
    @DisplayName("group-of-groups is followed one level down and further")
    void nestedGroups() {
        group(GROUP, NESTED_GROUP);
        group(NESTED_GROUP, AGENT);
        agent(AGENT, WORKFLOW);
        workflow(WORKFLOW, RULE_SET);

        Set<String> reached = resolver.referencedResourceIds(GROUP);

        assertTrue(reached.contains(NESTED_GROUP), "the nested group itself is part of what was shared");
        assertTrue(reached.contains(AGENT), "the nested group's member agent: " + reached);
        assertTrue(reached.containsAll(Set.of(WORKFLOW, RULE_SET)), "reached: " + reached);
    }

    @Test
    @DisplayName("a group containing itself terminates and still yields its other members")
    void selfReferentialGroup() {
        group(GROUP, GROUP, AGENT);
        agent(AGENT, WORKFLOW);
        workflow(WORKFLOW);

        Set<String> reached = resolver.referencedResourceIds(GROUP);

        assertFalse(reached.contains(GROUP), "the root never appears in its own result");
        assertTrue(reached.contains(AGENT), "the cycle must not eat the legitimate member: " + reached);
    }

    @Test
    @DisplayName("an id that is neither agent nor group yields an empty graph, not an error")
    void unknownRoot() {
        assertTrue(resolver.referencedResourceIds("0000000000000000000000").isEmpty());
    }
}
