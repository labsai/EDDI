/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
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
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.FileUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B1 — what a selective export is allowed to leave out.
 * <p>
 * Snippets and schedules are selected through their own request parameters, not
 * through {@code selectedResources}. Filtering them by
 * {@code selectedResources} turned every selective export from a client built
 * against the previous contract into a silent partial backup: schedules had
 * never appeared in the export preview at all, and the snippet row's selection
 * key had just changed from the snippet's name to its resource id — so such a
 * client sends a set holding neither, and got back an archive with no snippets
 * and no schedules. With the import side now genuinely restoring schedules,
 * that loss stayed invisible until a restore came up with its cron jobs gone.
 * <p>
 * The archive contents are sampled while the ZIP is being built, because the
 * export deletes its scratch tree as soon as it returns.
 */
class RestExportServiceSelectionContractTest {

    private static final String AGENT_ID = "b1selectiveexportagent";
    private static final String WORKFLOW_ID = "aaaa11112222333344445555";
    private static final String LLM_ID = "bbbb11112222333344445555";
    private static final String SNIPPET_ID = "cccc11112222333344445555";
    private static final String OTHER_SNIPPET_ID = "dddd11112222333344445555";
    private static final String SCHEDULE_ID = "eeee11112222333344445555";
    private static final String OTHER_SCHEDULE_ID = "ffff11112222333344445555";

    private static final String SNIPPET_FILE = "snippets/" + SNIPPET_ID + ".snippet.json";
    private static final String SCHEDULE_FILE = "schedules/" + SCHEDULE_ID + ".schedule.json";

    private RestExportService exportService;

    private Path tmpDir;
    private Path exportRoot;

    /** Archive contents, as '/'-separated paths relative to the ZIP root. */
    private final List<String> archivedFiles = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var llmStore = mock(ILlmStore.class);
        var snippetStore = mock(IPromptSnippetStore.class);
        var scheduleStore = mock(IScheduleStore.class);
        var secretScrubber = mock(SecretScrubber.class);
        var zipArchive = mock(IZipArchive.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                mock(IDictionaryStore.class), mock(IRuleSetStore.class), mock(IApiCallsStore.class),
                llmStore, mock(IPropertySetterStore.class), mock(IOutputStore.class),
                mock(IMcpCallsStore.class), mock(IRagStore.class), snippetStore,
                jsonSerialization, zipArchive, secretScrubber, scheduleStore,
                mock(ResourceAccessGuard.class), mock(BackupMetrics.class));

        tmpDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));
        exportRoot = tmpDir.resolve("export");

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(new WorkflowConfiguration());
        when(llmStore.read(LLM_ID, 1)).thenReturn(new LlmConfiguration(List.of()));

        // The workflow points at one LLM config; that config references one snippet.
        String workflowJson = "{\"workflowSteps\":[{\"type\":\"eddi://ai.labs.llm\",\"config\":"
                + "{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1\"}}]}";
        String llmJson = "{\"tasks\":[{\"systemMessage\":\"{snippets.greeting}\"}]}";
        when(jsonSerialization.serialize(any())).thenAnswer(inv -> {
            Object value = inv.getArgument(0);
            if (value instanceof WorkflowConfiguration) {
                return workflowJson;
            }
            if (value instanceof LlmConfiguration) {
                return llmJson;
            }
            return "{}";
        });
        when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));

        var agentDescriptor = new DocumentDescriptor();
        agentDescriptor.setName("Selective Export Agent");
        when(documentDescriptorStore.readDescriptorWithHistory(anyString(), any())).thenReturn(agentDescriptor);

        var snippetDescriptor = new DocumentDescriptor();
        snippetDescriptor.setResource(URI.create(
                "eddi://ai.labs.snippet/snippetstore/snippets/" + SNIPPET_ID + "?version=1"));
        when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                eq(false), any())).thenReturn(List.of(snippetDescriptor));
        var snippet = new PromptSnippet();
        snippet.setName("greeting");
        when(snippetStore.read(SNIPPET_ID, 1)).thenReturn(snippet);

        var schedule = new ScheduleConfiguration();
        schedule.setId(SCHEDULE_ID);
        schedule.setName("nightly consolidation");
        when(scheduleStore.readSchedulesByAgentId(AGENT_ID)).thenReturn(List.of(schedule));

        doAnswer(inv -> {
            recordArchiveContents(Paths.get(inv.getArgument(0, String.class)));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(exportRoot);
        deleteRecursively(tmpDir.resolve("archives"));
    }

    @Test
    @DisplayName("a selectedResources set that names only extensions still exports snippets and schedules")
    void extensionOnlySelectionKeepsSnippetsAndSchedules() {
        // Exactly what a client built against the previous contract sends: the
        // extension ids it read out of the preview, and nothing else.
        exportService.exportAgent(AGENT_ID, 1, LLM_ID, null, null);

        assertTrue(archivedFiles.contains(SNIPPET_FILE),
                "a selection that never enumerated snippets must not silently drop them, got " + archivedFiles);
        assertTrue(archivedFiles.contains(SCHEDULE_FILE),
                "a selection that never enumerated schedules must not silently drop them, got " + archivedFiles);
    }

    @Test
    @DisplayName("selectedSnippets filters snippets, and only snippets")
    void snippetSelectionIsHonoured() {
        exportService.exportAgent(AGENT_ID, 1, LLM_ID, OTHER_SNIPPET_ID, null);

        assertFalse(archivedFiles.contains(SNIPPET_FILE),
                "a snippet the caller did not select must stay out of the archive, got " + archivedFiles);
        assertTrue(archivedFiles.contains(SCHEDULE_FILE),
                "selecting snippets must not take the schedules with it, got " + archivedFiles);
    }

    @Test
    @DisplayName("selectedSchedules filters schedules, and only schedules")
    void scheduleSelectionIsHonoured() {
        exportService.exportAgent(AGENT_ID, 1, LLM_ID, null, OTHER_SCHEDULE_ID);

        assertFalse(archivedFiles.contains(SCHEDULE_FILE),
                "a schedule the caller did not select must stay out of the archive, got " + archivedFiles);
        assertTrue(archivedFiles.contains(SNIPPET_FILE),
                "selecting schedules must not take the snippets with it, got " + archivedFiles);
    }

    @Test
    @DisplayName("an empty selectedSnippets/selectedSchedules means none, not all")
    void presentButEmptySelectionMeansNone() {
        // The parameter being present at all is the client saying it speaks the
        // newer contract; empty is how it says "I unticked every one of them".
        exportService.exportAgent(AGENT_ID, 1, null, "", "");

        assertFalse(archivedFiles.contains(SNIPPET_FILE),
                "an explicitly empty snippet selection must export no snippets, got " + archivedFiles);
        assertFalse(archivedFiles.contains(SCHEDULE_FILE),
                "an explicitly empty schedule selection must export no schedules, got " + archivedFiles);
    }

    @Test
    @DisplayName("a full export still carries every referenced snippet and every schedule")
    void fullExportIsUnfiltered() {
        exportService.exportAgent(AGENT_ID, 1, null, null, null);

        assertTrue(archivedFiles.contains(SNIPPET_FILE), archivedFiles.toString());
        assertTrue(archivedFiles.contains(SCHEDULE_FILE), archivedFiles.toString());
    }

    /**
     * Samples the scratch tree the ZIP is built from. It has to happen here: the
     * export removes the tree before it returns.
     */
    private void recordArchiveContents(Path zipRoot) throws IOException {
        try (var paths = Files.walk(zipRoot)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> zipRoot.relativize(path).toString().replace('\\', '/'))
                    .forEach(archivedFiles::add);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
