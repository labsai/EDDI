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
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How {@link StructuralMatcher} answers when the target side of a sync cannot
 * be read, and how it compares content that carries a scrubbed secret.
 * <p>
 * Two behaviours are load-bearing here. First, the difference between "that
 * agent does not exist" (404, the operator mistyped an id) and "I could not
 * read it" (500, the datastore is unwell): reporting an outage as not-found
 * sends the operator to look for a resource that is there, and a client that
 * retries a 404 by creating the agent duplicates it. Second, an export scrubs
 * every credential, so comparing the raw source against a live target made
 * every configuration holding an API key differ by the placeholder alone — such
 * a resource could never SKIP, and a sync that changed nothing still burned a
 * version on exactly the agents that matter most.
 */
@DisplayName("StructuralMatcher — unreadable targets and scrubbed content")
class StructuralMatcherFailurePathTest {

    private static final String TARGET_AGENT_ID = "112233445566aabbccddeeff";
    private static final String WORKFLOW_ID = "aabbccddeeff112233445566";
    private static final String LLM_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DICT_ID = "cccccccccccccccccccccccc";
    private static final String LLM_KEY = "eddi://ai.labs.llm#0/config";

    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IRestPromptSnippetStore snippetStore;
    private IRestWorkflowStore workflowStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IJsonSerialization jsonSerialization;
    private StructuralMatcher matcher;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        snippetStore = mock(IRestPromptSnippetStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        jsonSerialization = mock(IJsonSerialization.class);

        matcher = new StructuralMatcher(agentStore, documentDescriptorStore, snippetStore,
                workflowStore, restInterfaceFactory, jsonSerialization);

        when(snippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
        when(jsonSerialization.deserialize(anyString()))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));

        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setName("Target Agent");
        agentDescriptor.setResource(URI.create(
                "eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptor(TARGET_AGENT_ID, null)).thenReturn(agentDescriptor);
    }

    /**
     * The store's own not-found is passed through as a 404 naming the agent. It
     * used to be turned into a generic "could not read", which is the same answer a
     * datastore outage gives.
     */
    @Test
    @DisplayName("a target agent that does not exist is a 404, not a 500")
    void missingTargetAgentIsNotFound() {
        // Sneaky-thrown, exactly as the REST proxy delivers it in production: the
        // store's checked exceptions are not declared on this signature.
        doAnswer(throwing(new IResourceStore.ResourceNotFoundException("no such agent")))
                .when(agentStore).readAgent(TARGET_AGENT_ID, 1);

        var thrown = assertThrows(NotFoundException.class,
                () -> matcher.buildPreview(sourceWithNoWorkflows(), TARGET_AGENT_ID, true));

        assertTrue(thrown.getMessage().contains(TARGET_AGENT_ID), thrown.getMessage());
    }

    /**
     * Anything that is not the store's not-found is a server fault. Reporting a
     * datastore failure as "target agent not found" is what sent operators looking
     * for a resource that was there all along.
     */
    @Test
    @DisplayName("a target agent that cannot be read for any other reason is a 500")
    void unreadableTargetAgentIsAServerError() {
        doAnswer(throwing(new IResourceStore.ResourceStoreException("connection reset")))
                .when(agentStore).readAgent(TARGET_AGENT_ID, 1);

        var thrown = assertThrows(InternalServerErrorException.class,
                () -> matcher.buildPreview(sourceWithNoWorkflows(), TARGET_AGENT_ID, true));

        assertTrue(thrown.getMessage().contains("connection reset"), thrown.getMessage());
    }

    /**
     * A store answering null for an existing id is still "does not exist" to the
     * caller.
     */
    @Test
    @DisplayName("a target agent the store answers null for is a 404")
    void nullTargetAgentIsNotFound() {
        when(agentStore.readAgent(TARGET_AGENT_ID, 1)).thenReturn(null);

        assertThrows(NotFoundException.class,
                () -> matcher.buildPreview(sourceWithNoWorkflows(), TARGET_AGENT_ID, true));
    }

    /**
     * A NotFoundException the REST layer already raised must reach the caller
     * unchanged rather than be re-wrapped into a second one whose message hides the
     * first.
     */
    @Test
    @DisplayName("a NotFoundException the store already raised is passed straight through")
    void alreadyMappedNotFoundIsPassedThrough() {
        var notFound = new NotFoundException("agent gone");
        when(agentStore.readAgent(TARGET_AGENT_ID, 1)).thenThrow(notFound);

        var thrown = assertThrows(NotFoundException.class,
                () -> matcher.buildPreview(sourceWithNoWorkflows(), TARGET_AGENT_ID, true));

        assertSame(notFound, thrown);
    }

    /**
     * An unreadable target <em>workflow</em> is not an unreadable target agent: the
     * preview is still worth showing. The workflow is reported as an UPDATE, which
     * is the safe reading — the operator can see the sync intends to write it.
     */
    @Test
    @DisplayName("an unreadable target workflow leaves the preview standing, reporting UPDATE")
    void unreadableTargetWorkflowStillPreviews() throws Exception {
        stubTargetAgentWithOneWorkflow();
        when(workflowStore.readWorkflow(WORKFLOW_ID, 1))
                .thenThrow(new IllegalStateException("workflow unreadable"));

        var sourceExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
        var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                new WorkflowConfiguration(), Map.of(LLM_KEY, sourceExt));

        ImportPreview preview = matcher.buildPreview(sourceWith(sourceWf), TARGET_AGENT_ID, true);

        ResourceDiff wfDiff = diffOf(preview, "src-wf-1");
        assertEquals(DiffAction.UPDATE, wfDiff.action());
        // The extensions could not be read either, so the source's own extension has
        // nothing to match against and is reported as new rather than dropped.
        assertEquals(DiffAction.CREATE, diffOf(preview, "src-ext-1").action());
    }

    /**
     * The dictionary branch of the typed-store dispatch, which is the one a parser
     * step's regular dictionaries go through. Matching on the workflow step type
     * instead of the resource authority resolved every extension to "unknown", so
     * each of these branches is worth pinning.
     */
    @Test
    @DisplayName("a target dictionary is read through the dictionary store and compared")
    void dictionaryExtensionIsReadFromItsOwnStore() throws Exception {
        stubTargetAgentWithOneWorkflow();

        var targetWorkflow = new WorkflowConfiguration();
        targetWorkflow.setWorkflowSteps(new ArrayList<>(List.of(
                step("eddi://ai.labs.parser", Map.of("dictionaries", List.of(Map.of("config",
                        Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/"
                                + DICT_ID + "?version=1"))))))));
        when(workflowStore.readWorkflow(WORKFLOW_ID, 1)).thenReturn(targetWorkflow);

        var dictionaryStore = mock(IRestDictionaryStore.class);
        var targetDictionary = new DictionaryConfiguration();
        when(restInterfaceFactory.get(IRestDictionaryStore.class)).thenReturn(dictionaryStore);
        when(dictionaryStore.readRegularDictionary(DICT_ID, 1, "", "", 0, 0)).thenReturn(targetDictionary);

        String dictKey = "eddi://ai.labs.parser#0/extensions/dictionaries/0/config";
        var sourceExt = new ExtensionSourceData("src-dict-1", "Dictionary", "regulardictionary",
                "eddi://ai.labs.parser", mapper.writeValueAsString(targetDictionary));
        var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                targetWorkflow, Map.of(dictKey, sourceExt));

        ImportPreview preview = matcher.buildPreview(sourceWith(sourceWf), TARGET_AGENT_ID, true);

        // Identical content on both sides: the sync must leave it alone.
        assertEquals(DiffAction.SKIP, diffOf(preview, "src-dict-1").action());
    }

    /**
     * The headline of the secret-neutral comparison: the export replaced the API
     * key with a placeholder, everything else is identical, and the sync must
     * therefore write nothing. Comparing the raw source instead reported UPDATE for
     * every agent holding a credential.
     */
    @Test
    @DisplayName("a config differing only by a scrubbed secret compares equal and is skipped")
    void scrubbedSecretDoesNotMakeAConfigDiffer() throws Exception {
        String written = extensionActionFor(
                "{\"apiKey\":\"${vault:REDACTED}\",\"model\":\"gpt-4\"}",
                "{\"apiKey\":\"sk-live-abc\",\"model\":\"gpt-4\"}").action().name();

        assertEquals(DiffAction.SKIP.name(), written);
    }

    /**
     * The neutralisation must not hide a real change. Restoring the target's key
     * still leaves the model different, so this one is an UPDATE.
     */
    @Test
    @DisplayName("a real change beside a scrubbed secret is still an UPDATE")
    void realChangeBesideAScrubbedSecretIsStillAnUpdate() throws Exception {
        assertEquals(DiffAction.UPDATE,
                extensionActionFor("{\"apiKey\":\"${vault:REDACTED}\",\"model\":\"gpt-4\"}",
                        "{\"apiKey\":\"sk-live-abc\",\"model\":\"gpt-3\"}").action());
    }

    /**
     * When the merge cannot be performed at all, the comparison falls back to the
     * raw source. The result is an UPDATE — the safe direction: the operator is
     * shown a write that may be unnecessary rather than not shown one that matters.
     */
    @Test
    @DisplayName("content that cannot be parsed falls back to comparing the raw source")
    void unparseableContentFallsBackToTheRawComparison() throws Exception {
        // doThrow, not when(...).thenThrow: the when-form would first invoke the
        // answer already installed in setUp with an empty argument.
        doThrow(new IOException("not JSON")).when(jsonSerialization).deserialize(anyString());

        assertEquals(DiffAction.UPDATE,
                extensionActionFor("{\"apiKey\":\"${vault:REDACTED}\",\"model\":\"gpt-4\"}",
                        "{\"apiKey\":\"sk-live-abc\",\"model\":\"gpt-4\"}").action());
    }

    /**
     * The version shown beside the agent row is best-effort. A descriptor store
     * that throws must cost the version number, not the whole preview.
     */
    @Test
    @DisplayName("a descriptor store that throws costs the version, not the preview")
    void descriptorFailureLeavesTheVersionUnknown() throws Exception {
        var targetConfig = new AgentConfiguration();
        targetConfig.setWorkflows(List.of());
        when(agentStore.readAgent(eq(TARGET_AGENT_ID), anyInt())).thenReturn(targetConfig);
        doAnswer(throwing(new IResourceStore.ResourceStoreException("descriptor unreadable")))
                .when(documentDescriptorStore).readDescriptor(eq(TARGET_AGENT_ID), isNull());

        ImportPreview preview = matcher.buildPreview(sourceWithNoWorkflows(), TARGET_AGENT_ID, true);

        ResourceDiff agentDiff = diffOf(preview, "src-1");
        assertNotNull(agentDiff);
        assertEquals(TARGET_AGENT_ID, agentDiff.targetId());
        assertNull(agentDiff.targetVersion(),
                "an unknown version must be reported as unknown, not guessed");
    }

    /** Sneaky-throws a checked store exception the way the REST proxy does. */
    private static Answer<Object> throwing(Exception exception) {
        return invocation -> {
            throw exception;
        };
    }

    // ==================== Helpers ====================

    /**
     * Runs one LLM extension through the matcher and returns its diff row.
     */
    private ResourceDiff extensionActionFor(String sourceContent, String targetContent) throws Exception {
        stubTargetAgentWithOneWorkflow();

        var targetWorkflow = new WorkflowConfiguration();
        targetWorkflow.setWorkflowSteps(new ArrayList<>(List.of(
                step("eddi://ai.labs.llm", null,
                        Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2")))));
        when(workflowStore.readWorkflow(WORKFLOW_ID, 1)).thenReturn(targetWorkflow);

        var llmStore = mock(IRestLlmStore.class);
        var targetLlm = new LlmConfiguration(List.of());
        when(restInterfaceFactory.get(IRestLlmStore.class)).thenReturn(llmStore);
        when(llmStore.readLlm(LLM_ID, 2)).thenReturn(targetLlm);
        // The target's config as JSON — serializeSafe is what the matcher calls, so
        // this is where the target content enters the comparison.
        when(jsonSerialization.serialize(targetLlm)).thenReturn(targetContent);

        var sourceExt = new ExtensionSourceData("src-ext-1", "GPT Config", "langchain",
                "eddi://ai.labs.llm", sourceContent);
        var sourceWf = new WorkflowSourceData("src-wf-1", "Workflow 1", 0,
                targetWorkflow, Map.of(LLM_KEY, sourceExt));

        return diffOf(matcher.buildPreview(sourceWith(sourceWf), TARGET_AGENT_ID, true), "src-ext-1");
    }

    private void stubTargetAgentWithOneWorkflow() throws Exception {
        var targetConfig = new AgentConfiguration();
        targetConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.readAgent(eq(TARGET_AGENT_ID), anyInt())).thenReturn(targetConfig);

        var wfDescriptor = new DocumentDescriptor();
        wfDescriptor.setName("Target Workflow");
        when(documentDescriptorStore.readDescriptor(WORKFLOW_ID, null)).thenReturn(wfDescriptor);
    }

    private static WorkflowConfiguration.WorkflowStep step(String type,
                                                           Map<String, Object> extensions,
                                                           Map<String, Object> config) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create(type));
        step.setExtensions(extensions == null ? new HashMap<>() : new HashMap<>(extensions));
        step.setConfig(config == null ? new HashMap<>() : new HashMap<>(config));
        return step;
    }

    private static WorkflowConfiguration.WorkflowStep step(String type, Map<String, Object> extensions) {
        return step(type, extensions, null);
    }

    private static ResourceDiff diffOf(ImportPreview preview, String sourceId) {
        return preview.resources().stream()
                .filter(diff -> sourceId.equals(diff.sourceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + sourceId + " in " + preview.resources()));
    }

    private IResourceSource sourceWithNoWorkflows() {
        return sourceWith();
    }

    private IResourceSource sourceWith(WorkflowSourceData... workflows) {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        return new IResourceSource() {
            @Override
            public AgentSourceData readAgent() {
                return new AgentSourceData("src-1", "Source Agent", agentConfig);
            }

            @Override
            public List<WorkflowSourceData> readWorkflows() {
                return List.of(workflows);
            }

            @Override
            public List<IResourceSource.SnippetSourceData> readSnippets() {
                return List.of();
            }
        };
    }
}
