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
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
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
 * The dictionary is matched like any other resource — keyed under the parser
 * document — and the parser is compared with its references swapped for the
 * target's counterparts. Compared as written, the ids would differ on every
 * sync; paired by position instead, a dictionary replaced by another would have
 * compared equal.
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
    private static final String PARSER_KEY = "eddi://ai.labs.parser#0/config";
    private static final String DOCUMENT_KEY = PARSER_KEY + WorkflowExtensions.DOCUMENT_MARKER
            + "/extensions/dictionaries/";

    private final ObjectMapper mapper = new ObjectMapper();
    private StructuralMatcher matcher;
    private String targetDictionaryJson;

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

        // The wizard shape: a parser step naming a parser document, nothing inline.
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

        var targetDictionary = dictionary("hello");
        targetDictionaryJson = mapper.writeValueAsString(targetDictionary);
        var dictionaryStore = mock(IRestDictionaryStore.class);
        when(dictionaryStore.readRegularDictionary(DICT_T, 2, "", "", 0, 0)).thenReturn(targetDictionary);
        doReturn(dictionaryStore).when(restInterfaceFactory).get(IRestDictionaryStore.class);
    }

    @Test
    @DisplayName("the same parser and dictionary on both sides preview as SKIP, although every id differs")
    void sameParserIsSkip() {
        ImportPreview preview = preview(parserNaming(DICT + DICT_S + "?version=1"),
                Map.of(DOCUMENT_KEY + "0/config", dictionaryExtension(DICT_S, targetDictionaryJson)));

        assertEquals(DiffAction.SKIP, row(preview, "parser").action());
        ResourceDiff dictionary = row(preview, "regulardictionary");
        assertEquals(DiffAction.SKIP, dictionary.action());
        assertEquals(DICT_T, dictionary.targetId(), "the dictionary the parser names must be matched to the target's");
    }

    @Test
    @DisplayName("a dictionary replaced by another at the same place is a change, and so is its parser")
    void replacedDictionaryIsAChange() throws Exception {
        // Positional pairing would have compared the parser equal and synced nothing.
        ImportPreview preview = preview(parserNaming(DICT + DICT_S2 + "?version=1"),
                Map.of(DOCUMENT_KEY + "0/config",
                        dictionaryExtension(DICT_S2, mapper.writeValueAsString(dictionary("goodbye")))));

        assertEquals(DiffAction.UPDATE, row(preview, "regulardictionary").action());
        assertEquals(DiffAction.UPDATE, row(preview, "parser").action(),
                "the parser has to be written to name the dictionary's new version");
    }

    @Test
    @DisplayName("a dictionary added to the parser is a CREATE, and its parser an UPDATE")
    void addedDictionaryIsCreated() throws Exception {
        Map<String, ExtensionSourceData> dictionaries = new LinkedHashMap<>();
        dictionaries.put(DOCUMENT_KEY + "0/config", dictionaryExtension(DICT_S, targetDictionaryJson));
        dictionaries.put(DOCUMENT_KEY + "1/config",
                dictionaryExtension(DICT_S2, mapper.writeValueAsString(dictionary("goodbye"))));
        String twoDictionaries = parserNaming(DICT + DICT_S + "?version=1").replace("]}",
                ",{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":\""
                        + DICT + DICT_S2 + "?version=1\"}}]}");

        ImportPreview preview = preview(twoDictionaries, dictionaries);

        assertEquals(DiffAction.CREATE, preview.resources().stream()
                .filter(diff -> DICT_S2.equals(diff.sourceId())).findFirst().orElseThrow().action());
        assertEquals(DiffAction.UPDATE, row(preview, "parser").action());
    }

    // ==================== Fixtures ====================

    private ImportPreview preview(String sourceParserJson, Map<String, ExtensionSourceData> dictionaries) {
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();
        extensions.put(PARSER_KEY, new ExtensionSourceData("src-parser", "Parser", "parser", "eddi://ai.labs.parser",
                sourceParserJson));
        extensions.putAll(dictionaries);

        IResourceSource source = mock(IResourceSource.class);
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", new AgentConfiguration()));
        when(source.readSnippets()).thenReturn(List.of());
        when(source.readWorkflows()).thenReturn(List.of(new WorkflowSourceData("src-wf", "Workflow", 0,
                new WorkflowConfiguration(), extensions)));
        return matcher.buildPreview(source, TARGET_AGENT, true);
    }

    private static ExtensionSourceData dictionaryExtension(String sourceId, String contentJson) {
        return new ExtensionSourceData(sourceId, "Dictionary", "regulardictionary", "eddi://ai.labs.parser",
                contentJson);
    }

    private static DictionaryConfiguration dictionary(String word) {
        var entry = new DictionaryConfiguration.WordConfiguration();
        entry.setWord(word);
        entry.setExpressions("greeting(" + word + ")");
        var dictionary = new DictionaryConfiguration();
        dictionary.setWords(new ArrayList<>(List.of(entry)));
        return dictionary;
    }

    private static ResourceDiff row(ImportPreview preview, String type) {
        return preview.resources().stream()
                .filter(diff -> type.equals(diff.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " row in " + preview.resources()));
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
