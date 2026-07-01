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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.backup.model.ExportPreview;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
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
                scheduleStore);
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
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);
            // Should not throw; no snippets directory creation attempted
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), new LinkedHashSet<>());
            verify(documentDescriptorStore, never()).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("null descriptors from store — returns early")
        void nullDescriptors() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(null);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), names);
        }

        @Test
        @DisplayName("empty descriptors from store — returns early")
        void emptyDescriptors() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);

            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(Collections.emptyList());

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), names);
        }

        @Test
        @DisplayName("snippet with null name is skipped")
        void snippetWithNullNameSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);

            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/aabbccddeeff112233445566?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of(desc));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName(null); // null name → skip
            when(snippetStore.read("aabbccddeeff112233445566", 1)).thenReturn(snippet);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), names);
        }

        @Test
        @DisplayName("snippet not in referenced set is skipped")
        void snippetNotReferencedSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);

            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/aabbccddeeff112233445566?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of(desc));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName("farewell"); // not in referenced set
            when(snippetStore.read("aabbccddeeff112233445566", 1)).thenReturn(snippet);

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting"); // only "greeting" is referenced
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), names);
        }

        @Test
        @DisplayName("ResourceNotFoundException on snippet read is handled gracefully")
        void snippetResourceNotFound() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSnippets", java.nio.file.Path.class, Set.class);
            method.setAccessible(true);

            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/aabbccddeeff112233445566?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.snippet"), anyString(), anyInt(), anyInt(), eq(false)))
                    .thenReturn(List.of(desc));
            when(snippetStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            Set<String> names = new LinkedHashSet<>();
            names.add("greeting");
            // Should not throw
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test-snippets"), names);
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
                    "exportSchedules", String.class, java.nio.file.Path.class);
            method.setAccessible(true);

            when(scheduleStore.readSchedulesByAgentId("agent1")).thenReturn(Collections.emptyList());

            method.invoke(exportService, "agent1", java.nio.file.Files.createTempDirectory("test-sched"));
            // No files should be written
        }

        @Test
        @DisplayName("schedule export exception is handled gracefully")
        void scheduleExportException() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "exportSchedules", String.class, java.nio.file.Path.class);
            method.setAccessible(true);

            when(scheduleStore.readSchedulesByAgentId("agent1"))
                    .thenThrow(new RuntimeException("DB error"));

            // Should not throw
            assertDoesNotThrow(() -> method.invoke(exportService, "agent1",
                    java.nio.file.Files.createTempDirectory("test-sched")));
        }
    }

    // =========================================================
    // prepareZipFilename — via reflection
    // =========================================================

    @Nested
    @DisplayName("prepareZipFilename")
    class PrepareZipFilenameTests {

        @Test
        @DisplayName("non-empty descriptor name is URL-encoded in filename")
        void nonEmptyName() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("My Agent");
            String result = (String) method.invoke(exportService, descriptor, "id123", 2);

            assertTrue(result.contains("My+Agent-"));
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
    // writeSelectedConfigs — via reflection
    // =========================================================

    @Nested
    @DisplayName("writeSelectedConfigs")
    class WriteSelectedConfigsTests {

        @Test
        @DisplayName("null selectedIds writes all configs")
        void nullSelectedIdsWritesAll() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "writeSelectedConfigs", java.nio.file.Path.class, Map.class, String.class, Set.class);
            method.setAccessible(true);
            // Should call writeConfigs with all configs — just verify no NPE
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test"),
                    Collections.emptyMap(), "ext", null);
        }

        @Test
        @DisplayName("non-null selectedIds filters configs")
        void nonNullSelectedIdsFilters() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "writeSelectedConfigs", java.nio.file.Path.class, Map.class, String.class, Set.class);
            method.setAccessible(true);
            // Should call writeConfigs with filtered configs
            method.invoke(exportService, java.nio.file.Files.createTempDirectory("test"),
                    Collections.emptyMap(), "ext", Set.of("r1"));
        }
    }
}
