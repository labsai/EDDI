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
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A repeated sync of an agent whose parser document names a dictionary.
 * <p>
 * The parser document holds the dictionary's id itself, and ids differ between
 * instances. Written as the source holds it, the target's parser would name a
 * dictionary that exists only on the source — so it is written after the
 * dictionaries, with each reference swapped for the target's own.
 */
@DisplayName("UpgradeExecutor — parser documents on a repeated sync")
class UpgradeExecutorParserTest {

    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WF_ID = "bbccddeeff112233445566aa";
    private static final String PARSER_T = "ccddeeff112233445566aabb";
    private static final String DICT_T = "ddeeff112233445566aabbcc";
    private static final String DICT_S = "eeff112233445566aabbccdd";

    private static final String DICT = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/";
    private static final String PARSER = "eddi://ai.labs.parser/parserstore/parsers/";

    private final ObjectMapper mapper = new ObjectMapper();

    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IDocumentDescriptorStore descriptorStore;
    private StructuralMatcher structuralMatcher;
    private IRestParserStore parserStore;
    private IRestDictionaryStore dictionaryStore;
    private IParserStore parserDocuments;
    private UpgradeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        structuralMatcher = mock(StructuralMatcher.class);
        parserStore = mock(IRestParserStore.class);
        dictionaryStore = mock(IRestDictionaryStore.class);
        parserDocuments = mock(IParserStore.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);

        executor = new UpgradeExecutor(agentStore, workflowStore, mock(IRestPromptSnippetStore.class),
                jsonSerialization, structuralMatcher, descriptorStore, mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class));

        lenient().when(jsonSerialization.deserialize(anyString(), eq(ParserConfiguration.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), ParserConfiguration.class));
        lenient().when(jsonSerialization.deserialize(anyString(), eq(DictionaryConfiguration.class)))
                .thenReturn(new DictionaryConfiguration());
        lenient().when(parserStore.updateParser(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
        lenient().when(dictionaryStore.updateRegularDictionary(anyString(), anyInt(), any()))
                .thenReturn(Response.ok().build());

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=2"))));
        when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(null);
        lenient().when(descriptorStore.readCurrentDescriptor(AGENT_ID)).thenReturn(descriptorAt(AGENT_ID, 3));
        lenient().when(agentStore.readAgent(AGENT_ID, 3)).thenReturn(agentConfig);
        lenient().when(agentStore.updateAgent(eq(AGENT_ID), eq(3), any())).thenReturn(Response.ok().build());
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt()))
                .thenAnswer(inv -> descriptorAt(inv.getArgument(0), inv.getArgument(1)));

        var parserStep = new WorkflowConfiguration.WorkflowStep();
        parserStep.setType(URI.create("eddi://ai.labs.parser"));
        parserStep.setConfig(new LinkedHashMap<>(Map.of("uri", PARSER + PARSER_T + "?version=2")));
        parserStep.setExtensions(new LinkedHashMap<>(Map.of("dictionaries", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("config", new LinkedHashMap<>(Map.of("uri", DICT + DICT_T + "?version=2")))))))));
        var targetWorkflow = new WorkflowConfiguration();
        targetWorkflow.setWorkflowSteps(new ArrayList<>(List.of(parserStep)));
        lenient().when(workflowStore.readWorkflow(WF_ID, 2)).thenReturn(targetWorkflow);
        lenient().when(workflowStore.updateWorkflow(eq(WF_ID), eq(2), any())).thenReturn(Response.ok().build());
    }

    @Test
    @DisplayName("a dictionary moved on this run drags its parser onto the new version, with the target's ids")
    void parserFollowsTheDictionaryItNames() throws Exception {
        // The parser itself did not change — the preview says SKIP — but the
        // dictionary it names did. Left alone, the target's parser would keep
        // naming the version this run just superseded.
        givenPreview(DiffAction.UPDATE, DiffAction.SKIP);

        UpgradeResult result = withStoresInCdi(() -> executor.executeUpgrade(source(), AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "nothing should have failed, got: " + result.failures());

        // The dictionary first: the parser is repointed at what that write produced.
        InOrder order = inOrder(dictionaryStore, parserStore);
        order.verify(dictionaryStore).updateRegularDictionary(eq(DICT_T), eq(2), any());
        var written = ArgumentCaptor.forClass(ParserConfiguration.class);
        order.verify(parserStore).updateParser(eq(PARSER_T), eq(2), written.capture());

        String parserJson = mapper.writeValueAsString(written.getValue());
        assertTrue(parserJson.contains(DICT + DICT_T + "?version=3"),
                "the parser must name the target's dictionary at the version just written, was: " + parserJson);
        assertFalse(parserJson.contains(DICT_S),
                "the source's dictionary id names nothing on the target, was: " + parserJson);
    }

    @Test
    @DisplayName("a parser whose dictionaries were not touched is left alone")
    void untouchedParserIsNotWritten() throws Exception {
        givenPreview(DiffAction.SKIP, DiffAction.SKIP);

        UpgradeResult result = withStoresInCdi(() -> executor.executeUpgrade(source(), AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "got: " + result.failures());
        verify(parserStore, never()).updateParser(anyString(), anyInt(), any());
        verify(dictionaryStore, never()).updateRegularDictionary(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a parser the target's step names but the target does not have is recreated, and the step repointed")
    void danglingTargetParserIsRecreated() throws Exception {
        // The state every agent promoted before parser documents travelled is in:
        // its parser step names the SOURCE's parser id. The matcher cannot read it,
        // so the preview says CREATE — which the executor refuses when there is no
        // step to wire the resource into. Here there is one.
        givenPreviewWithDanglingParser();
        when(parserDocuments.read(PARSER_T, 2)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        when(parserDocuments.create(any())).thenReturn(resourceId("0123456789abcdef01234567", 1));

        UpgradeResult result = withStoresInCdi(() -> executor.executeUpgrade(source(), AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "the missing parser should have been recreated, got: " + result.failures());
        assertEquals(1, result.created());

        var created = ArgumentCaptor.forClass(ParserConfiguration.class);
        verify(parserDocuments).create(created.capture());
        String parserJson = mapper.writeValueAsString(created.getValue());
        assertTrue(parserJson.contains(DICT + DICT_T + "?version=2"),
                "with no target copy to pair with, the parser is repointed through the workflow's own match, was: "
                        + parserJson);

        var workflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).updateWorkflow(eq(WF_ID), eq(2), workflow.capture());
        assertEquals(PARSER + "0123456789abcdef01234567?version=1",
                workflow.getValue().getWorkflowSteps().getFirst().getConfig().get("uri"),
                "the existing step must now name the recreated parser");
    }

    @Test
    @DisplayName("a parser that is merely unreadable is not replaced by a copy")
    void unreadableTargetParserIsNotReplaced() throws Exception {
        // A timeout, an access failure: the resource may well be there. Recreating
        // it would swap a live resource for a copy and orphan the original.
        givenPreviewWithDanglingParser();
        when(parserDocuments.read(PARSER_T, 2)).thenThrow(new IResourceStore.ResourceStoreException("unavailable"));

        UpgradeResult result = withStoresInCdi(() -> executor.executeUpgrade(source(), AGENT_ID, null, null));

        verify(parserDocuments, never()).create(any());
        assertEquals(1, result.failures().size(), "the CREATE is refused as before, got: " + result.failures());
    }

    // ==================== Fixtures ====================

    private static String parserNaming(String dictionaryUri) {
        return "{\"extensions\":{\"dictionaries\":[{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\","
                + "\"config\":{\"uri\":\"" + dictionaryUri + "\"}}]},\"config\":{}}";
    }

    private void givenPreview(DiffAction dictionaryAction, DiffAction parserAction) throws Exception {
        var diffs = List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 3,
                        "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "Workflow", DiffAction.SKIP, WF_ID, 2, "position",
                        null, null, 0),
                new ResourceDiff("src-parser", "parser", "Parser", parserAction, PARSER_T, 2, "type",
                        parserNaming(DICT + DICT_S + "?version=1"), parserNaming(DICT + DICT_T + "?version=2"), -1),
                new ResourceDiff(DICT_S, "regulardictionary", "Dictionary", dictionaryAction, DICT_T, 2, "type",
                        "{\"words\":[{\"word\":\"new\"}]}", "{\"words\":[]}", -1));
        when(structuralMatcher.buildPreview(any(), eq(AGENT_ID), eq(true)))
                .thenReturn(new ImportPreview("src-agent", "Agent", AGENT_ID, "Agent", diffs));
    }

    private void givenPreviewWithDanglingParser() throws Exception {
        var diffs = List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 3,
                        "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "Workflow", DiffAction.SKIP, WF_ID, 2, "position",
                        null, null, 0),
                new ResourceDiff("src-parser", "parser", "Parser", DiffAction.CREATE, null, null, null,
                        parserNaming(DICT + DICT_S + "?version=1"), null, -1),
                new ResourceDiff(DICT_S, "regulardictionary", "Dictionary", DiffAction.SKIP, DICT_T, 2, "type",
                        "{\"words\":[]}", "{\"words\":[]}", -1));
        when(structuralMatcher.buildPreview(any(), eq(AGENT_ID), eq(true)))
                .thenReturn(new ImportPreview("src-agent", "Agent", AGENT_ID, "Agent", diffs));
    }

    private static IResourceId resourceId(String id, int version) {
        return new IResourceId() {
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

    /**
     * The parser listed before its dictionary, as a workflow scan lists them — the
     * executor is what has to put the dictionary first.
     */
    private IResourceSource source() {
        var source = mock(IResourceSource.class);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", agentConfig));
        when(source.readSnippets()).thenReturn(List.of());

        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();
        extensions.put("eddi://ai.labs.parser#0/config",
                new ExtensionSourceData("src-parser", "Parser", "parser", "eddi://ai.labs.parser",
                        parserNaming(DICT + DICT_S + "?version=1")));
        extensions.put("eddi://ai.labs.parser#0/extensions/dictionaries/0/config",
                new ExtensionSourceData(DICT_S, "Dictionary", "regulardictionary", "eddi://ai.labs.parser",
                        "{\"words\":[{\"word\":\"new\"}]}"));
        when(source.readWorkflows()).thenReturn(List.of(new WorkflowSourceData(
                "src-wf", "Workflow", 0, new WorkflowConfiguration(), extensions)));
        return source;
    }

    private static DocumentDescriptor descriptorAt(String resourceId, Integer version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + resourceId + "?version=" + version));
        return descriptor;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T withStoresInCdi(Supplier<T> action) {
        MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
        try (cdiMock) {
            var cdi = Mockito.mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            var parsers = (Instance<IRestParserStore>) Mockito.mock(Instance.class);
            when(cdi.select(IRestParserStore.class)).thenReturn(parsers);
            when(parsers.get()).thenReturn(parserStore);
            var dictionaries = (Instance<IRestDictionaryStore>) Mockito.mock(Instance.class);
            when(cdi.select(IRestDictionaryStore.class)).thenReturn(dictionaries);
            when(dictionaries.get()).thenReturn(dictionaryStore);
            var documents = (Instance<IParserStore>) Mockito.mock(Instance.class);
            when(cdi.select(IParserStore.class)).thenReturn(documents);
            when(documents.get()).thenReturn(parserDocuments);
            return action.get();
        }
    }
}
