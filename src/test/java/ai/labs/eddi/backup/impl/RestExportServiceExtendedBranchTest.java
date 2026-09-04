/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.backup.model.ExportPreview;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended tests for {@link RestExportService} covering export paths, snippet
 * extraction, schedule export, and preview export branches.
 */
@DisplayName("RestExportService — Extended Export Coverage")
class RestExportServiceExtendedBranchTest {

    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IWorkflowStore workflowStore;
    @Mock
    private IDictionaryStore dictionaryStore;
    @Mock
    private IRuleSetStore ruleSetStore;
    @Mock
    private IApiCallsStore apiCallsStore;
    @Mock
    private ILlmStore llmStore;
    @Mock
    private IPropertySetterStore propertySetterStore;
    @Mock
    private IOutputStore outputStore;
    @Mock
    private IMcpCallsStore mcpCallsStore;
    @Mock
    private IRagStore ragStore;
    @Mock
    private IPromptSnippetStore snippetStore;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IZipArchive zipArchive;
    @Mock
    private SecretScrubber secretScrubber;
    @Mock
    private IScheduleStore scheduleStore;

    private RestExportService exportService;

    @BeforeEach
    void setUp() {
        openMocks(this);
        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                dictionaryStore, ruleSetStore, apiCallsStore, llmStore,
                propertySetterStore, outputStore, mcpCallsStore, ragStore,
                snippetStore, jsonSerialization, zipArchive, secretScrubber,
                scheduleStore, mock(ResourceAccessGuard.class), mock(BackupMetrics.class));
    }

    // =========================================================
    // exportSnippets — via reflection
    // =========================================================

    @Nested
    @DisplayName("exportSnippets")
    class ExportSnippetsTests {

        @Test
        @DisplayName("empty referenced names — returns early")
        void emptyReferencedNames() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            Path agentPath = Files.createTempDirectory("test-snippets");
            method.invoke(exportService, agentPath, new LinkedHashSet<>(), null);

            // The 6-argument overload is the one production calls — it carries the
            // access scope. Verifying the 5-argument one, as this test used to,
            // could never fail no matter what exportSnippets did.
            verify(documentDescriptorStore, never())
                    .readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), any());
            assertFalse(Files.exists(agentPath.resolve("snippets")),
                    "an early return must not leave an empty snippets directory behind");
        }

        @Test
        @DisplayName("null descriptors from store — returns early")
        void nullDescriptors() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(null);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            Path agentPath = Files.createTempDirectory("test-snippets");
            method.invoke(exportService, agentPath, names, null);

            assertFalse(Files.exists(agentPath.resolve("snippets")),
                    "nothing to export means nothing written");
        }

        @Test
        @DisplayName("empty descriptors from store — returns early")
        void emptyDescriptors() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(Collections.emptyList());

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            Path agentPath = Files.createTempDirectory("test-snippets");
            method.invoke(exportService, agentPath, names, null);

            assertFalse(Files.exists(agentPath.resolve("snippets")),
                    "nothing to export means nothing written");
        }

        @Test
        @DisplayName("snippet with null name is skipped")
        void snippetWithNullNameSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            String snippetId = "aabbccddeeff112233445566";
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/" + snippetId + "?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(List.of(desc));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName(null); // null name → skip
            when(snippetStore.read(snippetId, 1)).thenReturn(snippet);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            Path agentPath = Files.createTempDirectory("test-snippets");
            method.invoke(exportService, agentPath, names, null);

            assertFalse(Files.exists(agentPath.resolve("snippets").resolve(snippetId + ".snippet.json")),
                    "a nameless snippet cannot be matched against the referenced set, so it must not be written");
        }

        @Test
        @DisplayName("snippet not in referenced set is skipped")
        void snippetNotReferencedSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            String snippetId = "aabbccddeeff112233445566";
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/" + snippetId + "?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(List.of(desc));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName("farewell"); // not in referenced set
            when(snippetStore.read(snippetId, 1)).thenReturn(snippet);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting"); // only "greeting" is referenced
            Path agentPath = Files.createTempDirectory("test-snippets");
            method.invoke(exportService, agentPath, names, null);

            assertFalse(Files.exists(agentPath.resolve("snippets").resolve(snippetId + ".snippet.json")),
                    "a snippet this agent never references must stay out of the archive");
        }

        @Test
        @DisplayName("ResourceNotFoundException on snippet read is handled gracefully")
        void snippetResourceNotFound() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/aabbccddeeff112233445566?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(List.of(desc));
            when(snippetStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            // Should not throw
            method.invoke(exportService, Files.createTempDirectory("test-snippets"), names, null);
        }

        @Test
        @DisplayName("a deselected snippet is not written into the archive")
        void deselectedSnippetSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", Path.class, Set.class, Set.class);
            method.setAccessible(true);

            String snippetId = "aabbccddeeff112233445566";
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/" + snippetId + "?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false), any()))
                    .thenReturn(List.of(desc));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName("greeting");
            when(snippetStore.read(snippetId, 1)).thenReturn(snippet);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenReturn("{}");

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");

            Path agentPath = Files.createTempDirectory("test-snippets");
            // The preview renders snippet rows as deselectable; ignoring the selection
            // here put back exactly the deployment-specific prompt text a user unticked.
            method.invoke(exportService, agentPath, names, Set.of("some-other-id"));

            assertFalse(Files.exists(agentPath.resolve("snippets").resolve(snippetId + ".snippet.json")));

            // ...and a selected one is still written.
            method.invoke(exportService, agentPath, names, Set.of(snippetId));
            assertTrue(Files.exists(agentPath.resolve("snippets").resolve(snippetId + ".snippet.json")));
        }
    }

    // =========================================================
    // exportSchedules — via reflection
    // =========================================================

    @Nested
    @DisplayName("exportSchedules")
    class ExportSchedulesTests {

        @Test
        @DisplayName("empty schedules — returns early")
        void emptySchedules() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSchedules", String.class, Path.class, Set.class);
            method.setAccessible(true);

            when(scheduleStore.readSchedulesByAgentId("agent1")).thenReturn(Collections.emptyList());

            method.invoke(exportService, "agent1", Files.createTempDirectory("test-sched"), null);
            // No files should be written
        }

        @Test
        @DisplayName("schedule export exception is handled gracefully")
        void scheduleExportException() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSchedules", String.class, Path.class, Set.class);
            method.setAccessible(true);

            when(scheduleStore.readSchedulesByAgentId("agent1"))
                    .thenThrow(new RuntimeException("DB error"));

            // Should not throw
            assertDoesNotThrow(() -> method.invoke(exportService, "agent1",
                    Files.createTempDirectory("test-sched"), null));
        }

        @Test
        @DisplayName("a deselected schedule is not written into the archive")
        void deselectedScheduleSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSchedules", String.class, Path.class, Set.class);
            method.setAccessible(true);

            var kept = new ScheduleConfiguration();
            kept.setId("aabbccddeeff112233445566");
            kept.setName("nightly");
            var dropped = new ScheduleConfiguration();
            dropped.setId("bbccddeeff11223344556677");
            dropped.setName("hourly");
            when(scheduleStore.readSchedulesByAgentId("agent1")).thenReturn(List.of(kept, dropped));
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenReturn("{}");

            Path agentPath = Files.createTempDirectory("test-sched");
            method.invoke(exportService, "agent1", agentPath, Set.of(kept.getId()));

            Path schedulesDir = agentPath.resolve("schedules");
            assertTrue(Files.exists(schedulesDir.resolve(kept.getId() + ".schedule.json")));
            assertFalse(Files.exists(schedulesDir.resolve(dropped.getId() + ".schedule.json")));
        }
    }

    // =========================================================
    // prepareZipFilename — via reflection
    // =========================================================

    @Nested
    @DisplayName("prepareZipFilename")
    class PrepareZipFilenameTests {

        @Test
        @DisplayName("non-empty descriptor name is slugified into the filename")
        void nonEmptyName() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("My Agent");
            String result = (String) method.invoke(exportService, descriptor, "id123", 2);

            assertTrue(result.startsWith("My-Agent-"), result);
            assertTrue(result.endsWith("id123-2.zip"));
        }

        @Test
        @DisplayName("a non-ASCII name produces a downloadable filename")
        void nonAsciiNameIsDownloadable() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("Müller Bot");
            String result = (String) method.invoke(exportService, descriptor, "id123", 2);

            // URLEncoder produced "M%C3%BCller+Bot-…", JAX-RS decoded the path
            // parameter back on the way in, and the download endpoint's character
            // class then rejected both the decoded "ü" and the literal "%" — so the
            // export succeeded into an archive that could never be downloaded.
            assertTrue(result.matches("^[a-zA-Z0-9_.+\\-]+$"), result);
            assertTrue(result.startsWith("Muller-Bot-"), result);
            assertTrue(result.endsWith("id123-2.zip"));
        }

        @Test
        @DisplayName("null descriptor name uses only id and version")
        void nullName() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName(null);
            String result = (String) method.invoke(exportService, descriptor, "id123", 2);

            assertEquals("id123-2.zip", result);
        }

        @Test
        @DisplayName("empty descriptor name uses only id and version")
        void emptyName() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("");
            String result = (String) method.invoke(exportService, descriptor, "id123", 2);

            assertEquals("id123-2.zip", result);
        }
    }

    // =========================================================
    // previewExport
    // =========================================================

    @Nested
    @DisplayName("previewExport")
    class PreviewExportTests {

        @Test
        @DisplayName("valid agentId returns preview with resources")
        void validPreview() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read("validAgentId", 1)).thenReturn(config);

            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setName("Test Agent");
            when(documentDescriptorStore.readDescriptor("validAgentId", 1)).thenReturn(desc);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            ExportPreview preview = exportService.previewExport("validAgentId", 1);

            assertNotNull(preview);
            assertEquals("validAgentId", preview.agentId());
            assertEquals("Test Agent", preview.agentName());
            assertFalse(preview.resources().isEmpty());
        }

        @Test
        @DisplayName("null agent descriptor name sets null agentName")
        void nullDescriptor() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read("validAgentId", 1)).thenReturn(config);
            when(documentDescriptorStore.readDescriptor("validAgentId", 1)).thenReturn(null);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            ExportPreview preview = exportService.previewExport("validAgentId", 1);

            assertNull(preview.agentName());
        }

        @Test
        @DisplayName("agent with backslash in id throws BadRequestException")
        void backslashInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport("agent\\id", 1));
        }
    }

    // =========================================================
    // previewExport — what a client echoes back as a selection
    // =========================================================

    @Nested
    @DisplayName("previewExport row keys")
    class PreviewRowKeyTests {

        private static final String PREVIEW_AGENT_ID = "previewAgent";
        private static final String PREVIEW_WF_ID = "aaaa11112222333344445555";
        private static final String SNIPPET_ID = "bbbb11112222333344445555";
        private static final String SCHEDULE_ID = "cccc11112222333344445555";

        @BeforeEach
        void stubOneWorkflowReferencingOneSnippet() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of(URI.create(
                    "eddi://ai.labs.workflow/workflowstore/workflows/" + PREVIEW_WF_ID + "?version=1")));
            when(agentStore.read(PREVIEW_AGENT_ID, 1)).thenReturn(config);
            when(workflowStore.read(PREVIEW_WF_ID, 1)).thenReturn(new WorkflowConfiguration());

            DocumentDescriptor agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Preview Agent");
            when(documentDescriptorStore.readDescriptor(PREVIEW_AGENT_ID, 1)).thenReturn(agentDescriptor);

            // The workflow's own JSON is enough to carry a snippet reference.
            when(jsonSerialization.serialize(any()))
                    .thenReturn("{\"workflowSteps\":[{\"systemMessage\":\"{snippets.greeting}\"}]}");
        }

        private void stubSnippetDescriptorSweep(List<DocumentDescriptor> descriptors) throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(),
                    eq(false), any())).thenReturn(descriptors);
        }

        private ExportPreview.ExportableResource rowOfType(ExportPreview preview, String type) {
            return preview.resources().stream()
                    .filter(r -> type.equals(r.resourceType()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no '" + type + "' row in preview: " + preview.resources()));
        }

        @Test
        @DisplayName("a snippet row carries the snippet's resource id, like every other row")
        void snippetRowIsKeyedByResourceId() throws Exception {
            DocumentDescriptor snippetDescriptor = new DocumentDescriptor();
            snippetDescriptor.setResource(URI.create(
                    "eddi://ai.labs.snippet/snippetstore/snippets/" + SNIPPET_ID + "?version=1"));
            stubSnippetDescriptorSweep(List.of(snippetDescriptor));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName("greeting");
            when(snippetStore.read(SNIPPET_ID, 1)).thenReturn(snippet);

            ExportPreview preview = exportService.previewExport(PREVIEW_AGENT_ID, 1);

            var row = rowOfType(preview, "snippet");
            // exportSnippets filters on resourceId. A row keyed by the snippet's NAME
            // could never be matched by a client echoing it back, so the snippet was
            // silently dropped from the archive.
            assertEquals(SNIPPET_ID, row.resourceId());
            assertEquals("greeting", row.name(), "the name is still what the operator reads");
            assertEquals(Integer.valueOf(1), row.resourceVersion());
        }

        @Test
        @DisplayName("a snippet whose id cannot be resolved falls back to its name rather than vanishing")
        void snippetRowFallsBackToName() throws Exception {
            // Access-scoped listing, or a descriptor read that failed: both are caught
            // and logged at debug inside resolveSnippetIdsByName.
            stubSnippetDescriptorSweep(List.of());

            ExportPreview preview = exportService.previewExport(PREVIEW_AGENT_ID, 1);

            var row = rowOfType(preview, "snippet");
            assertEquals("greeting", row.resourceId(),
                    "an unresolvable snippet must still be listed, so the operator sees it exists");
            assertNull(row.resourceVersion());
        }

        @Test
        @DisplayName("the agent's schedules are listed as deselectable rows")
        void schedulesAppearInPreview() throws Exception {
            stubSnippetDescriptorSweep(List.of());

            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setId(SCHEDULE_ID);
            schedule.setName("nightly consolidation");
            when(scheduleStore.readSchedulesByAgentId(PREVIEW_AGENT_ID)).thenReturn(List.of(schedule));

            ExportPreview preview = exportService.previewExport(PREVIEW_AGENT_ID, 1);

            // Schedules are written into every archive but appeared in no preview, so
            // an operator could neither see what a restore would bring back nor build a
            // correct selectedSchedules set from what they were shown.
            var row = rowOfType(preview, "schedule");
            assertEquals(SCHEDULE_ID, row.resourceId());
            assertEquals("nightly consolidation", row.name());
            assertFalse(row.required(), "a schedule must be deselectable");
        }
    }

    // =========================================================
    // getAgentZipArchive — the download contract
    // =========================================================

    @Nested
    @DisplayName("getAgentZipArchive")
    class DownloadContractTests {

        private Path archiveDir;

        @BeforeEach
        void createArchiveDir() throws Exception {
            archiveDir = Paths.get(System.getProperty("user.dir"), "tmp", "archives");
            Files.createDirectories(archiveDir);
        }

        @Test
        @DisplayName("serves the archive as an attachment named after the file")
        void servesAsAttachment() throws Exception {
            Path archive = archiveDir.resolve("My-Agent-aaaa11112222333344445555-1.zip");
            Files.write(archive, new byte[]{0x50, 0x4b, 0x03, 0x04});
            Response response = null;
            try {
                response = exportService.getAgentZipArchive(archive.getFileName().toString());

                assertEquals(200, response.getStatus());
                // Without this the browser renders the ZIP inline (or saves it under
                // the endpoint's last path segment) instead of offering it by name.
                assertEquals("attachment; filename=\"" + archive.getFileName() + "\"",
                        response.getHeaderString("Content-Disposition"));
            } finally {
                // The entity is an open stream on the file, and Windows will not
                // delete a file while a handle is held.
                if (response != null && response.getEntity() instanceof InputStream stream) {
                    stream.close();
                }
                Files.deleteIfExists(archive);
            }
        }

        @Test
        @DisplayName("a missing archive is a 404 that quotes the retention window")
        void missingArchiveIsNotFound() {
            var ex = assertThrows(NotFoundException.class,
                    () -> exportService.getAgentZipArchive("never-written-aaaa1111-1.zip"));

            assertTrue(ex.getMessage().contains("never-written-aaaa1111-1.zip"), ex.getMessage());
            // The message used to claim archives "are not kept indefinitely" while
            // nothing deleted them at all; now it names the window that does.
            assertTrue(ex.getMessage().contains("60 minutes"), ex.getMessage());
        }
    }

    // =========================================================
    // writeSelectedConfigs — via reflection
    // =========================================================

    @Nested
    @DisplayName("writeSelectedConfigs")
    class WriteSelectedConfigsTests {

        @Test
        @DisplayName("null selectedIds writes all configs")
        void nullSelectedIdsWritesAll() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "writeSelectedConfigs", Path.class, Map.class, String.class, Set.class);
            method.setAccessible(true);
            // Should call writeConfigs with all configs — just verify no NPE
            method.invoke(exportService, Files.createTempDirectory("test"),
                    Collections.emptyMap(), "ext", null);
        }

        @Test
        @DisplayName("non-null selectedIds filters configs")
        void nonNullSelectedIdsFilters() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "writeSelectedConfigs", Path.class, Map.class, String.class, Set.class);
            method.setAccessible(true);
            // Should call writeConfigs with filtered configs
            method.invoke(exportService, Files.createTempDirectory("test"),
                    Collections.emptyMap(), "ext", Set.of("r1"));
        }
    }
}
