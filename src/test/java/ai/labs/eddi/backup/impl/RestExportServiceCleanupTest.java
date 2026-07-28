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
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D12 — export must not leave residue behind: the loose files the ZIP is built
 * from are removed once it exists, and the {@code unused/} tree (written but
 * never zipped or read by anything) is gone entirely.
 */
class RestExportServiceCleanupTest {

    private static final String AGENT_ID = "d12exportcleanupagent";
    private static final String WORKFLOW_ID = "eeee11112222333344445555";

    private IZipArchive zipArchive;
    private RestExportService exportService;

    private Path scratchDir;

    @BeforeEach
    void setUp() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var scheduleStore = mock(IScheduleStore.class);
        var secretScrubber = mock(SecretScrubber.class);
        zipArchive = mock(IZipArchive.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                mock(IDictionaryStore.class), mock(IRuleSetStore.class), mock(IApiCallsStore.class),
                mock(ILlmStore.class), mock(IPropertySetterStore.class), mock(IOutputStore.class),
                mock(IMcpCallsStore.class), mock(IRagStore.class), mock(IPromptSnippetStore.class),
                jsonSerialization, zipArchive, secretScrubber, scheduleStore);

        scratchDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"), AGENT_ID);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agentConfig);
        when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(new WorkflowConfiguration());

        var descriptor = new DocumentDescriptor();
        descriptor.setName("Export Cleanup Agent");
        when(documentDescriptorStore.readDescriptorWithHistory(anyString(), any())).thenReturn(descriptor);

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(scheduleStore.readSchedulesByAgentId(AGENT_ID)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        // Belt and braces: if an assertion fails mid-test, do not leave the repo dirty.
        deleteRecursively(scratchDir);
    }

    @Test
    @DisplayName("export deletes the scratch tree once the ZIP exists, and writes no unused/ tree")
    void exportLeavesNoResidue() throws Exception {
        AtomicBoolean scratchTreePresentWhileZipping = new AtomicBoolean();
        AtomicBoolean unusedTreeWritten = new AtomicBoolean();

        doAnswer(inv -> {
            scratchTreePresentWhileZipping.set(Files.exists(scratchDir.resolve("1")));
            unusedTreeWritten.set(Files.exists(scratchDir.resolve("unused")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null);

        assertTrue(scratchTreePresentWhileZipping.get(),
                "the exported files must still be on disk while the ZIP is being built");
        assertFalse(unusedTreeWritten.get(),
                "the dead unused/ tree is written but never zipped or read — it must not be produced");
        assertFalse(Files.exists(scratchDir),
                "export scratch tree was left behind: " + scratchDir);
    }

    @Test
    @DisplayName("a failing export still deletes the scratch tree")
    void failedExportLeavesNoResidue() throws Exception {
        doThrow(new IOException("disk full"))
                .when(zipArchive).createZip(anyString(), anyString(), any());

        assertThrows(IOException.class, () -> exportService.exportAgent(AGENT_ID, 1, null));

        assertFalse(Files.exists(scratchDir),
                "export scratch tree was left behind after a failure: " + scratchDir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
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
