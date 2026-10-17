/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3: a workflow may only point at configuration its author could read. The
 * guard is a mock here, so each test says exactly which resource it refuses;
 * {@code ResourceAccessGuardTest} covers how the guard itself decides.
 */
class RestWorkflowStoreReferenceAccessTest {

    private static final String WORKFLOW_ID = "aabbccddee1122334455aabb";
    private static final String OWN_LLM = "111111111111111111111111";
    private static final String FOREIGN_LLM = "222222222222222222222222";
    private static final String DICTIONARY = "333333333333333333333333";
    private static final String AGENT = "444444444444444444444444";

    private IWorkflowStore workflowStore;
    private ResourceAccessGuard guard;
    private RestWorkflowStore sut;

    @BeforeEach
    void setUp() throws Exception {
        workflowStore = mock(IWorkflowStore.class);
        guard = mock(ResourceAccessGuard.class);
        sut = new RestWorkflowStore(workflowStore, mock(ResourceClientLibrary.class), mock(IDocumentDescriptorStore.class),
                mock(IJsonSchemaCreator.class), guard);
        when(workflowStore.getCurrentResourceId(WORKFLOW_ID)).thenReturn(resourceId(WORKFLOW_ID, 1));
        when(workflowStore.create(any())).thenReturn(resourceId(WORKFLOW_ID, 1));
        when(workflowStore.update(eq(WORKFLOW_ID), eq(1), any())).thenReturn(2);
        doThrow(new ForbiddenException("Access denied: you do not have view access")).when(guard).requireAccess(eq(FOREIGN_LLM),
                eq(AccessLevel.VIEW), anyString());
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static String llm(String id) {
        return "eddi://ai.labs.llm/llmstore/llms/" + id + "?version=1";
    }

    private static WorkflowConfiguration workflow(String... llmIds) {
        var config = new WorkflowConfiguration();
        for (String id : llmIds) {
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.llm"));
            step.setConfig(new HashMap<>(Map.of("uri", llm(id))));
            config.getWorkflowSteps().add(step);
        }
        return config;
    }

    private static WorkflowStep parserWithDictionary(String dictionaryId) {
        var step = new WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.parser"));
        List<Object> dictionaries = new ArrayList<>();
        dictionaries.add(Map.of("type", "eddi://ai.labs.parser.dictionaries.regular", "config",
                Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + dictionaryId + "?version=1")));
        step.setExtensions(new HashMap<>(Map.of("dictionaries", dictionaries)));
        return step;
    }

    @Test
    @DisplayName("creating a workflow that points at another space's private configuration is refused, and nothing is stored")
    void createRefusesForeignReference() throws Exception {
        assertThrows(ForbiddenException.class, () -> sut.createWorkflow(workflow(OWN_LLM, FOREIGN_LLM)));

        verify(workflowStore, never()).create(any());
    }

    @Test
    @DisplayName("every step and extension reference is checked for VIEW; an agent reference only for USE")
    void createChecksEveryReference() throws Exception {
        var config = workflow(OWN_LLM);
        config.getWorkflowSteps().add(parserWithDictionary(DICTIONARY));
        var agentStep = new WorkflowStep();
        agentStep.setType(URI.create("eddi://ai.labs.llm"));
        agentStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=1")));
        config.getWorkflowSteps().add(agentStep);

        Response response = sut.createWorkflow(config);

        assertEquals(201, response.getStatus());
        verify(guard).requireAccess(eq(OWN_LLM), eq(AccessLevel.VIEW), anyString());
        verify(guard).requireAccess(eq(DICTIONARY), eq(AccessLevel.VIEW), anyString());
        verify(guard).requireUseAccess(eq(AGENT), anyString());
        verify(guard, never()).requireAccess(eq(AGENT), any(), anyString());
    }

    @Test
    @DisplayName("without enforced workspaces (or for an administrator) nothing is checked")
    void noOpWhenNotEnforced() throws Exception {
        var permissive = mock(ResourceAccessGuard.class);
        when(permissive.seesEverything()).thenReturn(true);
        var open = new RestWorkflowStore(workflowStore, mock(ResourceClientLibrary.class), mock(IDocumentDescriptorStore.class),
                mock(IJsonSchemaCreator.class), permissive);

        assertEquals(201, open.createWorkflow(workflow(FOREIGN_LLM)).getStatus());
        verify(permissive, never()).requireAccess(anyString(), any(), anyString());
        verify(permissive, never()).requireUseAccess(anyString(), anyString());
    }

    @Test
    @DisplayName("an update may keep a reference it already had, but not add one the caller cannot read")
    void updateChecksOnlyNewReferences() throws Exception {
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow(FOREIGN_LLM));

        assertEquals(200, sut.updateWorkflow(WORKFLOW_ID, 1, workflow(FOREIGN_LLM)).getStatus(),
                "a reference somebody with access made stays editable around");

        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow(OWN_LLM));
        var widened = workflow(OWN_LLM, FOREIGN_LLM);
        assertThrows(ForbiddenException.class, () -> sut.updateWorkflow(WORKFLOW_ID, 1, widened));
        verify(workflowStore, never()).update(WORKFLOW_ID, 1, widened);
    }

    @Test
    @DisplayName("re-pointing a step at another space's configuration is refused, prefix trick included")
    void updateResourceInWorkflowRefusesForeignTarget() throws Exception {
        // updateResourceUri matches the stored reference by prefix, so a target id that
        // is a prefix of the stored one replaces it with a DIFFERENT resource.
        String prefixId = OWN_LLM.substring(0, OWN_LLM.length() - 1);
        doThrow(new ForbiddenException("Access denied")).when(guard).requireAccess(eq(prefixId), eq(AccessLevel.VIEW), anyString());
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow(OWN_LLM)).thenReturn(workflow(OWN_LLM));

        assertThrows(ForbiddenException.class,
                () -> sut.updateResourceInWorkflow(WORKFLOW_ID, 1, URI.create("eddi://ai.labs.llm/llmstore/llms/" + prefixId + "?version=2")));
        verify(workflowStore, never()).update(anyString(), any(), any());
    }

    @Test
    @DisplayName("moving a step to another version of the same configuration needs nothing new")
    void updateResourceInWorkflowNewVersionAllowed() throws Exception {
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow(FOREIGN_LLM)).thenReturn(workflow(FOREIGN_LLM));

        Response response = sut.updateResourceInWorkflow(WORKFLOW_ID, 1, URI.create(llm(FOREIGN_LLM).replace("version=1", "version=2")));

        assertEquals(200, response.getStatus());
        verify(guard, never()).requireAccess(eq(FOREIGN_LLM), any(), anyString());
    }

    @Test
    @DisplayName("duplicating a workflow re-checks its references for the caller who will own the copy")
    void duplicateChecksReferences() throws Exception {
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(workflow(FOREIGN_LLM));

        assertThrows(ForbiddenException.class, () -> sut.duplicateWorkflow(WORKFLOW_ID, 1, false));
        verify(workflowStore, never()).create(any());
    }

    @Test
    @DisplayName("non-eddi and id-less references are not resources and are skipped")
    void skipsNonReferences() {
        var config = new WorkflowConfiguration();
        var step = new WorkflowStep();
        step.setConfig(new HashMap<>(Map.of("uri", "https://example.com/x")));
        config.getWorkflowSteps().add(step);
        var bare = new WorkflowStep();
        bare.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.llm")));
        config.getWorkflowSteps().add(bare);

        assertEquals(Map.of(), RestWorkflowStore.referencedResources(config));
        verifyNoInteractions(guard);
    }
}
