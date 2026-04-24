/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.expression;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
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

/**
 * Unit tests for {@link RestAction}.
 */
class RestActionTest {

    // Valid hex ID (≥18 chars required by RestUtilities.isValidId)
    private static final String VALID_ID = "aabb000011112222cccc";

    private IWorkflowStore workflowStore;
    private IRuleSetStore behaviorStore;
    private IApiCallsStore httpCallsStore;
    private IOutputStore outputStore;
    private RestAction restAction;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IWorkflowStore.class);
        behaviorStore = mock(IRuleSetStore.class);
        httpCallsStore = mock(IApiCallsStore.class);
        outputStore = mock(IOutputStore.class);
        restAction = new RestAction(workflowStore, behaviorStore, httpCallsStore, outputStore);
    }

    @Test
    @DisplayName("should read actions from rules store")
    void readsActionsFromRulesStore() throws Exception {
        var wfConfig = createWorkflowWithStep("eddi://ai.labs.rules", "rulesstore/rules");
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(behaviorStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("greet", "farewell"));

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertEquals(2, result.size());
        assertTrue(result.contains("greet"));
    }

    @Test
    @DisplayName("should read actions from apicalls store")
    void readsActionsFromApiCallsStore() throws Exception {
        var wfConfig = createWorkflowWithStep("eddi://ai.labs.apicalls", "apicallsstore/apicalls");
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(httpCallsStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("callWeather", "callNews"));

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertEquals(2, result.size());
        assertTrue(result.contains("callWeather"));
    }

    @Test
    @DisplayName("should read actions from output store")
    void readsActionsFromOutputStore() throws Exception {
        var wfConfig = createWorkflowWithStep("eddi://ai.labs.output", "outputstore/outputsets");
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(outputStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("display_welcome"));

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertEquals(1, result.size());
        assertEquals("display_welcome", result.get(0));
    }

    @Test
    @DisplayName("should return empty list for unknown step types")
    void emptyForUnknownTypes() throws Exception {
        var wfConfig = createWorkflowWithStep("eddi://ai.labs.llm", "llmstore/llms");
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should aggregate actions across multiple steps without duplicates")
    void aggregatesWithoutDuplicates() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var rulesStep = createStep("eddi://ai.labs.rules", "rulesstore/rules");
        var outputStep = createStep("eddi://ai.labs.output", "outputstore/outputsets");
        wfConfig.setWorkflowSteps(List.of(rulesStep, outputStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(behaviorStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("greet", "shared_action"));
        when(outputStore.readActions(VALID_ID, 1, "", 20))
                .thenReturn(List.of("shared_action", "display"));

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertEquals(3, result.size()); // no duplicate for shared_action
    }

    @Test
    @DisplayName("should propagate ResourceNotFoundException")
    void propagatesResourceNotFound() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> restAction.readActions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should propagate ResourceStoreException")
    void propagatesResourceStoreException() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> restAction.readActions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should handle empty workflow steps")
    void emptyWorkflowSteps() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(List.of());
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restAction.readActions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    // ==================== Helpers ====================

    private WorkflowConfiguration createWorkflowWithStep(String typeUri, String storePath) {
        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(List.of(createStep(typeUri, storePath)));
        return wfConfig;
    }

    private WorkflowStep createStep(String typeUri, String storePath) {
        var step = new WorkflowStep();
        step.setType(URI.create(typeUri));
        // URI format: eddi://ai.labs.xxx/storePath/VALID_HEX_ID?version=N
        step.setConfig(Map.of("uri",
                typeUri + "/" + storePath + "/" + VALID_ID + "?version=1"));
        return step;
    }
}
