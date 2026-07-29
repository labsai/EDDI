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
import ai.labs.eddi.datastore.IResourceStore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D12 — export must not leave residue behind: the loose files the ZIP is built
 * from are removed once it exists.
 * <p>
 * The scratch tree is per-request ({@code tmp/export/<uuid>/}), never
 * {@code tmp/<agentId>/}: cleanup must only ever be able to remove what this
 * very invocation created. The tests below pin both halves — the tree is gone
 * afterwards, and neither a concurrent export nor an unrelated file living in
 * {@code tmp/} is collateral damage.
 */
class RestExportServiceCleanupTest {

    private static final String AGENT_ID = "d12exportcleanupagent";
    private static final String WORKFLOW_ID = "eeee11112222333344445555";

    private IAgentStore agentStore;
    private IZipArchive zipArchive;
    private RestExportService exportService;

    private Path tmpDir;
    private Path exportRoot;
    private Path legacyScratchDir;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
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

        tmpDir = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));
        exportRoot = tmpDir.resolve("export");
        legacyScratchDir = tmpDir.resolve(AGENT_ID);

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
        deleteRecursively(exportRoot);
        deleteRecursively(legacyScratchDir);
    }

    @Test
    @DisplayName("export builds in tmp/export/<uuid>/ and deletes that tree once the ZIP exists")
    void exportLeavesNoResidue() throws Exception {
        AtomicBoolean scratchTreePresentWhileZipping = new AtomicBoolean();
        AtomicBoolean unusedTreeWritten = new AtomicBoolean();
        List<Path> zippedSources = new ArrayList<>();

        doAnswer(inv -> {
            Path source = Paths.get(inv.getArgument(0, String.class));
            zippedSources.add(source);
            scratchTreePresentWhileZipping.set(Files.exists(source.resolve(AGENT_ID + ".agent.json")));
            // the dead unused/ tree was written next to the version directory
            unusedTreeWritten.set(Files.exists(source.getParent().resolve("unused")));
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null);

        assertEquals(1, zippedSources.size());
        Path zippedSource = zippedSources.get(0).toAbsolutePath().normalize();

        // tmp/export/<uuid>/<agentId>/<version> — never the shared tmp/<agentId>.
        assertTrue(zippedSource.startsWith(exportRoot.toAbsolutePath().normalize()),
                "export must build inside its own tmp/export/<uuid> tree, but built in: " + zippedSource);
        assertEquals(Paths.get(AGENT_ID, "1"),
                exportRoot.toAbsolutePath().normalize().relativize(zippedSource).subpath(1, 3),
                "the agent/version layout inside the ZIP source must not change");
        assertFalse(Files.exists(legacyScratchDir),
                "the shared tmp/<agentId> tree must not be used any more: " + legacyScratchDir);

        assertTrue(scratchTreePresentWhileZipping.get(),
                "the exported files must still be on disk while the ZIP is being built");
        assertFalse(unusedTreeWritten.get(),
                "the dead unused/ tree is written but never zipped or read — it must not be produced");

        // The per-request tree (uuid dir), not just the agent sub-dir, is removed.
        Path perRequestRoot = zippedSource.getParent().getParent();
        assertFalse(Files.exists(perRequestRoot),
                "export scratch tree was left behind: " + perRequestRoot);
    }

    @Test
    @DisplayName("a failing export still deletes its scratch tree")
    void failedExportLeavesNoResidue() throws Exception {
        List<Path> zippedSources = new ArrayList<>();
        doAnswer(inv -> {
            zippedSources.add(Paths.get(inv.getArgument(0, String.class)));
            throw new IOException("disk full");
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        assertThrows(IOException.class, () -> exportService.exportAgent(AGENT_ID, 1, null));

        assertEquals(1, zippedSources.size());
        Path perRequestRoot = zippedSources.get(0).toAbsolutePath().normalize().getParent().getParent();
        assertFalse(Files.exists(perRequestRoot),
                "export scratch tree was left behind after a failure: " + perRequestRoot);
    }

    @Test
    @DisplayName("an export that overlaps another export of the same agent keeps its own files")
    void overlappingExportsDoNotDeleteEachOthersFiles() throws Exception {
        List<Path> zippedSources = new ArrayList<>();
        AtomicBoolean nestedExportStarted = new AtomicBoolean();
        AtomicBoolean ownFilesSurvivedOverlap = new AtomicBoolean();

        doAnswer(inv -> {
            Path source = Paths.get(inv.getArgument(0, String.class)).toAbsolutePath().normalize();
            zippedSources.add(source);
            if (nestedExportStarted.compareAndSet(false, true)) {
                // A second export of the same agent (same version, even) starts and
                // finishes — including its cleanup — while this one is mid-flight.
                exportService.exportAgent(AGENT_ID, 1, null);
                ownFilesSurvivedOverlap.set(Files.exists(source.resolve(AGENT_ID + ".agent.json")));
            }
            return null;
        }).when(zipArchive).createZip(anyString(), anyString(), any());

        exportService.exportAgent(AGENT_ID, 1, null);

        assertEquals(2, zippedSources.size(), "both the outer and the nested export must have been zipped");
        assertNotEquals(zippedSources.get(0), zippedSources.get(1),
                "two exports of the same agent must not share a scratch directory");
        assertTrue(ownFilesSurvivedOverlap.get(),
                "the concurrent export's cleanup deleted this export's files — the ZIP would have been empty");
    }

    @Test
    @DisplayName("a failed export never deletes unrelated files that happen to sit in tmp/")
    void failedExportDoesNotTouchUnrelatedTmpFiles() throws Exception {
        // The import staging root and finished export ZIPs share tmp/ with exports.
        Path importStagingMarker = tmpDir.resolve("import").resolve("d12-staged-import").resolve("agent.json");
        Files.createDirectories(importStagingMarker.getParent());
        Files.writeString(importStagingMarker, "{}");

        Path pendingDownload = tmpDir.resolve("d12pendingdownload-1.zip");
        Files.writeString(pendingDownload, "zip-bytes");

        try {
            when(agentStore.read("import", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no such agent"));
            when(agentStore.read("d12pendingdownload-1.zip", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no such agent"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> exportService.exportAgent("import", 1, null));
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> exportService.exportAgent("d12pendingdownload-1.zip", 1, null));

            assertTrue(Files.exists(importStagingMarker),
                    "exporting an agent named 'import' wiped the import staging tree: " + importStagingMarker);
            assertTrue(Files.exists(pendingDownload),
                    "exporting an agent named like a ZIP deleted a pending download: " + pendingDownload);
        } finally {
            deleteRecursively(importStagingMarker.getParent());
            Files.deleteIfExists(pendingDownload);
        }
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
