/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ExportPreview;
import ai.labs.eddi.backup.model.ExportPreview.ExportableResource;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export preview is what the wizard renders before anyone commits to a
 * download, and every row in it is stitched together from a different store.
 * <p>
 * That makes it the one screen where a single unavailable collaborator must
 * never be fatal: an agent the operator can still export is worth more than a
 * complete row set. Each store below is failed in turn, and the assertion is
 * always the same shape — the preview still comes back, the rows that do not
 * depend on the broken store are unchanged, and the row that does degrades in a
 * stated way rather than vanishing or carrying a lie.
 */
@DisplayName("RestExportService — a preview built from stores that fail")
class RestExportServicePreviewResilienceTest {

    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WORKFLOW_ID = "bbbbccddeeff112233445566";
    private static final String LLM_ID = "ccccccddeeff112233445566";
    private static final String SNIPPET_ID = "ddddccddeeff112233445566";
    /** A second snippet document, so "one descriptor drops out" is observable. */
    private static final String SECOND_SNIPPET_ID = "eeeeccddeeff112233445566";

    private static final String LLM_URI = "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1";

    private IDocumentDescriptorStore documentDescriptorStore;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private ILlmStore llmStore;
    private IPromptSnippetStore snippetStore;
    private IJsonSerialization jsonSerialization;
    private RestExportService exportService;

    /** The workflow JSON every test starts from: one LLM step, one snippet ref. */
    private String workflowJson = """
            {"workflowSteps":[{"type":"eddi://ai.labs.llm","config":{"uri":"%s"}}],
             "systemPrompt":"{snippets.cautious_mode}"}
            """.formatted(LLM_URI);

    @BeforeEach
    void setUp() throws Exception {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        llmStore = mock(ILlmStore.class);
        snippetStore = mock(IPromptSnippetStore.class);
        jsonSerialization = mock(IJsonSerialization.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                mock(IDictionaryStore.class), mock(IRuleSetStore.class), mock(IApiCallsStore.class), llmStore,
                mock(IPropertySetterStore.class), mock(IOutputStore.class), mock(IMcpCallsStore.class),
                mock(IRagStore.class), snippetStore, jsonSerialization, mock(IZipArchive.class),
                mock(SecretScrubber.class), mock(IScheduleStore.class), mock(ResourceAccessGuard.class),
                mock(BackupMetrics.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);

        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(new WorkflowConfiguration());
        when(jsonSerialization.serialize(any())).thenAnswer(invocation -> workflowJson);
        when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(descriptorNamed("Support Bot"));
        when(documentDescriptorStore.readDescriptor(WORKFLOW_ID, 1)).thenReturn(descriptorNamed("Main workflow"));
    }

    /**
     * The baseline every degradation below is measured against: with every store
     * answering, the LLM row carries its descriptor name and the snippet row
     * carries the snippet's real resource id and version — the id the selection
     * filter compares against, which is why a row keyed by the name could never be
     * ticked off by a client echoing it back.
     */
    @Test
    @DisplayName("with every store answering, rows carry their names and their real ids")
    void fullyResolvedPreview() throws Exception {
        when(documentDescriptorStore.readDescriptor(LLM_ID, 1)).thenReturn(descriptorNamed("GPT config"));
        stubSnippetLookup();

        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        ExportableResource llmRow = rowOfType(preview, "langchain");
        assertEquals(LLM_ID, llmRow.resourceId());
        assertEquals("GPT config", llmRow.name());

        ExportableResource snippetRow = rowOfType(preview, "snippet");
        assertEquals(SNIPPET_ID, snippetRow.resourceId(), "the row has to carry the id the selection filter compares");
        assertEquals(1, snippetRow.resourceVersion());
        assertEquals("cautious_mode", snippetRow.name());
    }

    /**
     * A descriptor read that throws costs the human-readable name of that one row.
     * The row itself stays: the archive is assembled from ids, so an unnamed row is
     * still an exportable row, and dropping it would silently shrink the archive.
     */
    @Test
    @DisplayName("a descriptor read that throws costs the row's name, not the row")
    void unreadableExtensionDescriptorCostsOnlyTheName() throws Exception {
        doAnswer(invocation -> {
            throw new IResourceStore.ResourceStoreException("descriptor store is down");
        }).when(documentDescriptorStore).readDescriptor(LLM_ID, 1);
        stubSnippetLookup();

        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        ExportableResource llmRow = rowOfType(preview, "langchain");
        assertEquals(LLM_ID, llmRow.resourceId(), "the row is what the export needs; the name is decoration");
        assertNull(llmRow.name());
        assertEquals(SNIPPET_ID, rowOfType(preview, "snippet").resourceId(),
                "one broken descriptor must not disturb the rest of the preview");
    }

    /**
     * A workflow carrying an extension URI that is not a URI at all costs that one
     * extension type and nothing else. The agent and workflow rows — and the other
     * seven extension types — are still listed, so the operator can see and export
     * everything that is intact.
     */
    @Test
    @DisplayName("an unparseable extension URI costs that extension type, not the preview")
    void unparseableExtensionUriCostsOnlyThatType() throws Exception {
        workflowJson = """
                {"workflowSteps":[{"type":"eddi://ai.labs.llm","config":{"uri":"eddi://ai.labs.llm/llmstore/llms/not a uri?version=1"}}],
                 "systemPrompt":"{snippets.cautious_mode}"}
                """;
        stubSnippetLookup();

        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        assertNotNull(rowOfType(preview, "agent"));
        assertNotNull(rowOfType(preview, "workflow"));
        assertTrue(preview.resources().stream().noneMatch(r -> "langchain".equals(r.resourceType())),
                "the malformed reference cannot become a row: " + preview.resources());
        assertEquals(SNIPPET_ID, rowOfType(preview, "snippet").resourceId());
    }

    /**
     * The snippet scan reads every extension config a second time to look for
     * {@code {snippets.x}} references inside them. A store that refuses that read
     * costs only the references it would have found — here the workflow's own
     * reference is still picked up, so the snippet row survives.
     */
    @Test
    @DisplayName("an extension store that will not read still leaves the workflow's own snippet refs")
    void unreadableExtensionContentStillFindsWorkflowSnippetRefs() throws Exception {
        doAnswer(invocation -> {
            throw new IResourceStore.ResourceStoreException("llm store is down");
        }).when(llmStore).read(LLM_ID, 1);
        stubSnippetLookup();

        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        assertEquals(SNIPPET_ID, rowOfType(preview, "snippet").resourceId());
        assertEquals(LLM_ID, rowOfType(preview, "langchain").resourceId(),
                "the row comes from the workflow's reference, not from reading the config");
    }

    /**
     * When the snippet's own id cannot be resolved the row falls back to the name
     * in both fields, with no version. It is a degraded row and it says so — a row
     * that claimed an id it had not verified would be ticked off against a snippet
     * that is not the one the agent references.
     * <p>
     * <strong>What this test does not pin.</strong> The contract asserted here is
     * real and reachable, but the {@code if (descriptors == null) return byName;}
     * guard in {@code resolveSnippetIdsByName} is <em>not</em> observable from
     * outside: delete it and the {@code NullPointerException} from iterating the
     * null listing lands in the method's own outer {@code catch (Exception e)},
     * which returns the same empty map — the map cannot have been filled yet, the
     * listing is the first thing the method reads. The guard buys a debug log line
     * that names the cause instead of an NPE stack, and nothing else. Treat it as a
     * log-level defence, like {@code recordCreatedSnippet}'s null-URI guard, not as
     * a line this test covers.
     */
    @Test
    @DisplayName("a snippet listing that returns null falls the row back to the name")
    void nullSnippetListingFallsBackToTheName() throws Exception {
        when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                anyBoolean(), any())).thenReturn(null);

        assertSnippetRowFellBackToItsName();
    }

    /** Same fallback when the listing throws outright. */
    @Test
    @DisplayName("a snippet listing that throws falls the row back to the name")
    void throwingSnippetListingFallsBackToTheName() throws Exception {
        doAnswer(invocation -> {
            throw new IResourceStore.ResourceStoreException("snippet descriptors unavailable");
        }).when(documentDescriptorStore).readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                anyBoolean(), any());

        assertSnippetRowFellBackToItsName();
    }

    /**
     * And when the listing works but the snippet behind one descriptor cannot be
     * read, that descriptor drops out of the lookup <em>and the sweep carries
     * on</em> — the snippet the agent actually references is found behind a later
     * descriptor and the row resolves normally.
     * <p>
     * Two descriptors is the whole point of the test. With one, "drops out" and
     * "aborts the entire lookup" are indistinguishable: both end in the name
     * fallback. The assertion here is on the id resolved <em>after</em> the
     * unreadable document, which only the per-descriptor catch can produce.
     */
    @Test
    @DisplayName("a snippet the store cannot read drops out, the lookup carries on to the next")
    void unreadableSnippetDropsOutOfTheLookup() throws Exception {
        when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                anyBoolean(), any()))
                .thenReturn(List.of(snippetDescriptor(SNIPPET_ID, 1), snippetDescriptor(SECOND_SNIPPET_ID, 2)));
        doAnswer(invocation -> {
            throw new IResourceStore.ResourceStoreException("snippet document is corrupt");
        }).when(snippetStore).read(SNIPPET_ID, 1);
        var snippet = new PromptSnippet();
        snippet.setName("cautious_mode");
        when(snippetStore.read(SECOND_SNIPPET_ID, 2)).thenReturn(snippet);

        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        ExportableResource snippetRow = rowOfType(preview, "snippet");
        assertEquals(SECOND_SNIPPET_ID, snippetRow.resourceId(),
                "the corrupt document costs its own descriptor, not the ones after it");
        assertEquals(2, snippetRow.resourceVersion());
        assertEquals("cautious_mode", snippetRow.name());
        assertEquals(LLM_ID, rowOfType(preview, "langchain").resourceId(),
                "the rest of the preview is unaffected");
    }

    // ==================== Helpers ====================

    private void assertSnippetRowFellBackToItsName() {
        ExportPreview preview = exportService.previewExport(AGENT_ID, 1);

        ExportableResource snippetRow = rowOfType(preview, "snippet");
        assertEquals("cautious_mode", snippetRow.resourceId(),
                "an unresolved snippet is named, not given an id that was never confirmed");
        assertEquals("cautious_mode", snippetRow.name());
        assertNull(snippetRow.resourceVersion());
        assertEquals(LLM_ID, rowOfType(preview, "langchain").resourceId(),
                "the rest of the preview is unaffected");
    }

    private void stubSnippetLookup() throws Exception {
        when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                anyBoolean(), any())).thenReturn(List.of(snippetDescriptor()));
        var snippet = new PromptSnippet();
        snippet.setName("cautious_mode");
        when(snippetStore.read(SNIPPET_ID, 1)).thenReturn(snippet);
    }

    private static DocumentDescriptor snippetDescriptor() {
        return snippetDescriptor(SNIPPET_ID, 1);
    }

    private static DocumentDescriptor snippetDescriptor(String snippetId, int version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create(
                "eddi://ai.labs.snippet/snippetstore/snippets/" + snippetId + "?version=" + version));
        return descriptor;
    }

    private static DocumentDescriptor descriptorNamed(String name) {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        return descriptor;
    }

    private static ExportableResource rowOfType(ExportPreview preview, String type) {
        return preview.resources().stream()
                .filter(resource -> type.equals(resource.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + type + "' row in " + preview.resources()));
    }
}
