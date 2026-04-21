package ai.labs.eddi.configs.output.rest.keys;

import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestOutputActionsTest {

    private static final String VALID_ID = "aabb000011112222cccc";

    private IWorkflowStore workflowStore;
    private IRuleSetStore behaviorStore;
    private IOutputStore outputStore;
    private RestOutputActions restOutputActions;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IWorkflowStore.class);
        behaviorStore = mock(IRuleSetStore.class);
        outputStore = mock(IOutputStore.class);
        restOutputActions = new RestOutputActions(workflowStore, behaviorStore, outputStore);
    }

    @Test
    @DisplayName("should read actions from behavior rules matching filter")
    void readsActionsFromBehaviorRules() throws Exception {
        var wfConfig = createWorkflowWithRulesStep();
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        var ruleConfig = new RuleConfiguration();
        ruleConfig.setActions(List.of("greet_user", "farewell_user", "other"));
        var groupConfig = new RuleGroupConfiguration();
        groupConfig.setRules(List.of(ruleConfig));
        var ruleSetConfig = new RuleSetConfiguration();
        ruleSetConfig.setBehaviorGroups(List.of(groupConfig));
        when(behaviorStore.read(VALID_ID, 1)).thenReturn(ruleSetConfig);

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "user", 20);
        assertEquals(2, result.size());
        assertTrue(result.contains("greet_user"));
        assertFalse(result.contains("other"));
    }

    @Test
    @DisplayName("should stop at limit")
    void stopsAtLimit() throws Exception {
        var wfConfig = createWorkflowWithRulesStep();
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        var ruleConfig = new RuleConfiguration();
        ruleConfig.setActions(List.of("a_match", "b_match", "c_match"));
        var groupConfig = new RuleGroupConfiguration();
        groupConfig.setRules(List.of(ruleConfig));
        var ruleSetConfig = new RuleSetConfiguration();
        ruleSetConfig.setBehaviorGroups(List.of(groupConfig));
        when(behaviorStore.read(VALID_ID, 1)).thenReturn(ruleSetConfig);

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "match", 2);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("should enforce limit across merged sources")
    void enforcesLimitAcrossMergedSources() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var step1 = new WorkflowStep();
        step1.setType(URI.create("eddi://ai.labs.rules"));
        step1.setConfig(Map.of("uri", "eddi://ai.labs.rules/rulesstore/rules/" + VALID_ID + "?version=1"));
        var step2 = new WorkflowStep();
        step2.setType(URI.create("eddi://ai.labs.output"));
        step2.setConfig(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/" + VALID_ID + "?version=1"));
        wfConfig.setWorkflowSteps(List.of(step1, step2));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        var ruleConfig = new RuleConfiguration();
        ruleConfig.setActions(List.of("rule_action1", "rule_action2"));
        var groupConfig = new RuleGroupConfiguration();
        groupConfig.setRules(List.of(ruleConfig));
        var ruleSetConfig = new RuleSetConfiguration();
        ruleSetConfig.setBehaviorGroups(List.of(groupConfig));
        when(behaviorStore.read(VALID_ID, 1)).thenReturn(ruleSetConfig);

        when(outputStore.readActions(VALID_ID, 1, "", 3)).thenReturn(List.of("out_action1", "out_action2"));

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "", 3);
        assertEquals(3, result.size());
        assertTrue(result.contains("rule_action1"));
        assertTrue(result.contains("rule_action2"));
    }

    @Test
    @DisplayName("should read from output store")
    void readsFromOutputStore() throws Exception {
        var wfConfig = createWorkflowWithOutputStep();
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(outputStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("display_welcome"));

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "", 20);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("should return sorted results")
    void returnsSortedResults() throws Exception {
        var wfConfig = createWorkflowWithOutputStep();
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(outputStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("zebra", "alpha", "middle"));

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "", 20);
        assertEquals(List.of("alpha", "middle", "zebra"), result);
    }

    @Test
    @DisplayName("should skip non-rules steps for rule extraction")
    void skipsNonRulesSteps() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var step = new WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + VALID_ID + "?version=1"));
        wfConfig.setWorkflowSteps(List.of(step));
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "", 20);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should propagate ResourceNotFoundException")
    void propagatesNotFound() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> restOutputActions.readOutputActions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should propagate ResourceStoreException")
    void propagatesStoreException() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceStoreException("DB error"));
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> restOutputActions.readOutputActions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should handle empty workflow")
    void emptyWorkflow() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(List.of());
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restOutputActions.readOutputActions("wf-1", 1, "", 20);
        assertTrue(result.isEmpty());
    }

    private WorkflowConfiguration createWorkflowWithRulesStep() {
        var wfConfig = new WorkflowConfiguration();
        var step = new WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rules"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rules/rulesstore/rules/" + VALID_ID + "?version=1"));
        wfConfig.setWorkflowSteps(List.of(step));
        return wfConfig;
    }

    private WorkflowConfiguration createWorkflowWithOutputStep() {
        var wfConfig = new WorkflowConfiguration();
        var step = new WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.output"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/" + VALID_ID + "?version=1"));
        wfConfig.setWorkflowSteps(List.of(step));
        return wfConfig;
    }
}
