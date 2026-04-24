/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.expression;

import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestExpression}.
 */
class RestExpressionTest {

    private static final String VALID_ID = "aabb000011112222cccc";
    private static final String VALID_ID_2 = "ddee000033334444ffff";

    private IWorkflowStore workflowStore;
    private IDictionaryStore dictionaryStore;
    private RestExpression restExpression;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IWorkflowStore.class);
        dictionaryStore = mock(IDictionaryStore.class);
        restExpression = new RestExpression(workflowStore, dictionaryStore);
    }

    @Test
    @DisplayName("should return expressions from parser dictionaries")
    void returnsExpressionsFromParserDictionaries() throws Exception {
        var wfConfig = createWorkflowWithParserStep(VALID_ID, 1);
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(dictionaryStore.readExpressions(VALID_ID, 1, "", "asc", 0, 20))
                .thenReturn(List.of("greeting(hello)", "farewell(bye)"));

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertEquals(2, result.size());
        assertTrue(result.contains("greeting(hello)"));
        assertTrue(result.contains("farewell(bye)"));
    }

    @Test
    @DisplayName("should skip non-parser workflow steps")
    void skipsNonParserSteps() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var rulesStep = new WorkflowStep();
        rulesStep.setType(URI.create("eddi://ai.labs.rules"));
        rulesStep.setExtensions(Map.of());
        wfConfig.setWorkflowSteps(List.of(rulesStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
        verifyNoInteractions(dictionaryStore);
    }

    @Test
    @DisplayName("should skip non-regular dictionaries in parser step")
    void skipsNonRegularDictionaries() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var parserStep = new WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));

        Map<String, Object> extension = new HashMap<>();
        extension.put("type", "eddi://ai.labs.parser.dictionaries.integer");
        extension.put("config", Map.of("uri", "eddi://ai.labs.dict/dictstore/dictionaries/" + VALID_ID + "?version=1"));

        parserStep.setExtensions(Map.of("dictionaries", List.of(extension)));
        wfConfig.setWorkflowSteps(List.of(parserStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should skip dictionaries extension with no type field")
    void skipsDictionariesWithNoType() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var parserStep = new WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));

        Map<String, Object> extension = new HashMap<>();
        extension.put("config", Map.of("uri", "eddi://ai.labs.dict/dictstore/dictionaries/" + VALID_ID + "?version=1"));

        parserStep.setExtensions(Map.of("dictionaries", List.of(extension)));
        wfConfig.setWorkflowSteps(List.of(parserStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should skip non-dictionaries extension keys")
    void skipsNonDictionaryExtensionKeys() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var parserStep = new WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));
        parserStep.setExtensions(Map.of("corrections", List.of()));
        wfConfig.setWorkflowSteps(List.of(parserStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should deduplicate expressions across multiple dictionaries")
    void deduplicatesExpressions() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        var parserStep = new WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));

        Map<String, Object> ext1 = new HashMap<>();
        ext1.put("type", "eddi://ai.labs.parser.dictionaries.regular");
        ext1.put("config", Map.of("uri", "eddi://ai.labs.dict/dictstore/dictionaries/" + VALID_ID + "?version=1"));

        Map<String, Object> ext2 = new HashMap<>();
        ext2.put("type", "eddi://ai.labs.parser.dictionaries.regular");
        ext2.put("config", Map.of("uri", "eddi://ai.labs.dict/dictstore/dictionaries/" + VALID_ID_2 + "?version=1"));

        parserStep.setExtensions(Map.of("dictionaries", List.of(ext1, ext2)));
        wfConfig.setWorkflowSteps(List.of(parserStep));

        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);
        when(dictionaryStore.readExpressions(VALID_ID, 1, "", "asc", 0, 20))
                .thenReturn(List.of("greeting(hello)", "shared(both)"));
        when(dictionaryStore.readExpressions(VALID_ID_2, 1, "", "asc", 0, 20))
                .thenReturn(List.of("shared(both)", "farewell(bye)"));

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("should propagate ResourceNotFoundException")
    void propagatesResourceNotFound() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceNotFoundException("Workflow not found"));

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> restExpression.readExpressions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should propagate ResourceStoreException")
    void propagatesResourceStoreException() throws Exception {
        when(workflowStore.read("wf-1", 1))
                .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> restExpression.readExpressions("wf-1", 1, "", 20));
    }

    @Test
    @DisplayName("should handle empty workflow steps")
    void emptyWorkflowSteps() throws Exception {
        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(List.of());
        when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

        List<String> result = restExpression.readExpressions("wf-1", 1, "", 20);

        assertTrue(result.isEmpty());
    }

    // ==================== Helpers ====================

    private WorkflowConfiguration createWorkflowWithParserStep(String dictId, int dictVersion) {
        var wfConfig = new WorkflowConfiguration();
        var parserStep = new WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));

        Map<String, Object> extension = new HashMap<>();
        extension.put("type", "eddi://ai.labs.parser.dictionaries.regular");
        extension.put("config", Map.of("uri",
                "eddi://ai.labs.dict/dictstore/dictionaries/" + dictId + "?version=" + dictVersion));

        parserStep.setExtensions(Map.of("dictionaries", List.of(extension)));
        wfConfig.setWorkflowSteps(List.of(parserStep));
        return wfConfig;
    }
}
