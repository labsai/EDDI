/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.AgentSourceData;
import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The preview of a parser document that names a dictionary.
 * <p>
 * The dictionary's id is the source's on one side and the target's on the other
 * — by construction, whatever the content. Compared as written, every such
 * parser would read as changed on every sync, and "nothing to promote" could
 * never be the answer for an agent that has one.
 */
@DisplayName("StructuralMatcher — parser documents")
class StructuralMatcherParserTest {

    private static final String TARGET_AGENT = "aabbccddeeff112233445566";
    private static final String WF_ID = "bbccddeeff112233445566aa";
    private static final String PARSER_T = "ccddeeff112233445566aabb";
    private static final String DICT_T = "ddeeff112233445566aabbcc";
    private static final String DICT_S = "eeff112233445566aabbccdd";
    private static final String DICT_S2 = "ff112233445566aabbccddee";

    private static final String DICT = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/";

    private final ObjectMapper mapper = new ObjectMapper();
    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        var agentStore = mock(IRestAgentStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var snippetStore = mock(IRestPromptSnippetStore.class);
        var workflowStore = mock(IRestWorkflowStore.class);
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        matcher = new StructuralMatcher(agentStore, descriptorStore, snippetStore, workflowStore,
                restInterfaceFactory, jsonSerialization);

        lenient().when(jsonSerialization.serialize(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
        lenient().when(jsonSerialization.deserialize(anyString()))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
        lenient().when(descriptorStore.readCurrentDescriptor(anyString()))
                .thenAnswer(inv -> descriptorAtVersionOne(inv.getArgument(0)));
        lenient().when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        var targetAgent = new AgentConfiguration();
        targetAgent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=1")));
        when(agentStore.readAgent(TARGET_AGENT, 1)).thenReturn(targetAgent);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.parser"));
        step.setConfig(new LinkedHashMap<>(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/" + PARSER_T + "?version=1")));
        var targetWorkflow = new WorkflowConfiguration();
        targetWorkflow.setWorkflowSteps(new ArrayList<>(List.of(step)));
        when(workflowStore.readWorkflow(WF_ID, 1)).thenReturn(targetWorkflow);

        var parserStore = mock(IRestParserStore.class);
        when(parserStore.readParser(PARSER_T, 1))
                .thenReturn(mapper.readValue(parserNaming(DICT + DICT_T + "?version=2"), ParserConfiguration.class));
        doReturn(parserStore).when(restInterfaceFactory).get(IRestParserStore.class);
    }

    @Test
    @DisplayName("the same parser on both sides previews as SKIP, although the ids differ")
    void sameParserIsSkip() {
        assertEquals(DiffAction.SKIP, parserRow(preview(parserNaming(DICT + DICT_S + "?version=1"))).action());
    }

    @Test
    @DisplayName("a parser that names another dictionary previews as UPDATE")
    void changedParserIsUpdate() {
        String twoDictionaries = parserNaming(DICT + DICT_S + "?version=1").replace("]}",
                ",{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":\""
                        + DICT + DICT_S2 + "?version=1\"}}]}");

        assertEquals(DiffAction.UPDATE, parserRow(preview(twoDictionaries)).action());
    }

    // ==================== Fixtures ====================

    private ImportPreview preview(String sourceParserJson) {
        IResourceSource source = mock(IResourceSource.class);
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", new AgentConfiguration()));
        when(source.readSnippets()).thenReturn(List.of());
        when(source.readWorkflows()).thenReturn(List.of(new WorkflowSourceData("src-wf", "Workflow", 0,
                new WorkflowConfiguration(),
                Map.of("eddi://ai.labs.parser#0/config",
                        new ExtensionSourceData("src-parser", "Parser", "parser", "eddi://ai.labs.parser",
                                sourceParserJson)))));
        return matcher.buildPreview(source, TARGET_AGENT, true);
    }

    private static ResourceDiff parserRow(ImportPreview preview) {
        return preview.resources().stream()
                .filter(diff -> "parser".equals(diff.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no parser row in " + preview.resources()));
    }

    private static String parserNaming(String dictionaryUri) {
        return "{\"extensions\":{\"dictionaries\":[{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\","
                + "\"config\":{\"uri\":\"" + dictionaryUri + "\"}}]},\"config\":{}}";
    }

    private static DocumentDescriptor descriptorAtVersionOne(String resourceId) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + resourceId + "?version=1"));
        return descriptor;
    }
}
