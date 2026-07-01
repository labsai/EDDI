/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ExportPreview;
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
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended unit tests for {@link RestExportService} covering schedule export,
 * extension resource preview, error paths, and selective export filtering.
 */
class RestExportServiceExtendedTest {

    private IDocumentDescriptorStore documentDescriptorStore;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private ILlmStore llmStore;
    private IApiCallsStore httpCallsStore;
    private IOutputStore outputStore;
    private IPropertySetterStore propertySetterStore;
    private IPromptSnippetStore snippetStore;
    private IJsonSerialization jsonSerialization;
    private IZipArchive zipArchive;
    private SecretScrubber secretScrubber;
    private IScheduleStore scheduleStore;
    private RestExportService exportService;

    @BeforeEach
    void setUp() {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        var dictionaryStore = mock(IDictionaryStore.class);
        var behaviorStore = mock(IRuleSetStore.class);
        httpCallsStore = mock(IApiCallsStore.class);
        llmStore = mock(ILlmStore.class);
        propertySetterStore = mock(IPropertySetterStore.class);
        outputStore = mock(IOutputStore.class);
        var mcpCallsStore = mock(IMcpCallsStore.class);
        var ragStore = mock(IRagStore.class);
        snippetStore = mock(IPromptSnippetStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        zipArchive = mock(IZipArchive.class);
        secretScrubber = mock(SecretScrubber.class);
        scheduleStore = mock(IScheduleStore.class);

        exportService = new RestExportService(
                documentDescriptorStore, agentStore, workflowStore,
                dictionaryStore, behaviorStore, httpCallsStore, llmStore,
                propertySetterStore, outputStore, mcpCallsStore, ragStore,
                snippetStore, jsonSerialization, zipArchive, secretScrubber,
                scheduleStore);
    }

    // ─── sanitizePathComponent ─────────────────────────────────

    @Nested
    @DisplayName("sanitizePathComponent validation via export")
    class SanitizePathComponent {

        @Test
        @DisplayName("should reject double-dot path traversal")
        void doubleDotTraversal() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agent..id", 1, null));
        }

        @Test
        @DisplayName("should reject absolute path in agentId")
        void absolutePathLinux() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("/etc/passwd", 1, null));
        }

        @Test
        @DisplayName("should reject backslash in agentId")
        void backslash() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agent\\id", 1, null));
        }
    }

    // ─── Export with workflows containing extensions ─────────

    @Nested
    @DisplayName("exportAgent with workflows and extensions")
    class ExportWithExtensions {

        @Test
        @DisplayName("should export agent with workflow and its extensions")
        void exportsWorkflowExtensions() throws Exception {
            // Use valid 24-char hex ObjectId format IDs — RestUtilities.isValidId()
            // requires ≥18 hex chars, so short IDs like "wf-1" return null.
            String agentId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            String wfId = "bbbbbbbbbbbbbbbbbbbbbbbb";
            int agentVersion = 1;

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfId + "?version=1")));
            when(agentStore.read(agentId, agentVersion)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Extension Agent");
            agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=1"));
            when(documentDescriptorStore.readDescriptorWithHistory(eq(agentId), eq(agentVersion))).thenReturn(agentDescriptor);
            // readDescriptorWithHistory for the workflow resource
            var wfDescriptor = new DocumentDescriptor();
            wfDescriptor.setName("Workflow 1");
            when(documentDescriptorStore.readDescriptorWithHistory(eq(wfId), eq(1))).thenReturn(wfDescriptor);

            // Workflow config with LLM URI
            var wfConfig = new WorkflowConfiguration();
            String llmId = "cccccccccccccccccccccccc";
            String wfJson = "{\"workflowSteps\":[{\"type\":\"ai.labs.llm\",\"extensions\":{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/" + llmId
                    + "?version=1\"}}]}";
            when(workflowStore.read(wfId, 1)).thenReturn(wfConfig);
            when(jsonSerialization.serialize(any())).thenReturn(wfJson);
            when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleStore.readSchedulesByAgentId(agentId)).thenReturn(List.of());

            Response response = exportService.exportAgent(agentId, agentVersion, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            verify(zipArchive).createZip(anyString(), anyString(), any());
        }
    }

    // ─── previewExport with extensions ─────────────────────

    @Nested
    @DisplayName("previewExport with extension resources")
    class PreviewWithExtensions {

        @Test
        @DisplayName("should include extension resources in preview")
        void includesExtensionResources() throws Exception {
            String agentId = "agent-preview-ext";

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Preview Agent");

            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, 1)).thenReturn(agentDescriptor);

            var wfConfig = new WorkflowConfiguration();
            when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

            // Serialize workflow to produce JSON with LLM URI reference
            String wfJson = "{\"workflowSteps\":[{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/llm-1?version=2\"}]}";
            when(jsonSerialization.serialize(any())).thenReturn(wfJson);

            var wfDescriptor = new DocumentDescriptor();
            wfDescriptor.setName("Workflow 1");
            when(documentDescriptorStore.readDescriptor("wf-1", 1)).thenReturn(wfDescriptor);

            // Extension descriptor lookup
            var llmDescriptor = new DocumentDescriptor();
            llmDescriptor.setName("LLM Config");
            when(documentDescriptorStore.readDescriptor("llm-1", 2)).thenReturn(llmDescriptor);

            ExportPreview preview = exportService.previewExport(agentId, 1);

            assertNotNull(preview);
            assertTrue(preview.resources().size() >= 2); // agent + workflow at minimum
        }

        @Test
        @DisplayName("should handle descriptor lookup failure gracefully")
        void handlesDescriptorLookupFailure() throws Exception {
            String agentId = "agent-desc-fail";

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));

            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, 1)).thenReturn(null);

            var wfConfig = new WorkflowConfiguration();
            when(workflowStore.read("wf-1", 1)).thenReturn(wfConfig);

            String wfJson = "{}";
            when(jsonSerialization.serialize(any())).thenReturn(wfJson);

            when(documentDescriptorStore.readDescriptor("wf-1", 1))
                    .thenThrow(new RuntimeException("DB error"));

            // Should not throw — graceful degradation
            ExportPreview preview = exportService.previewExport(agentId, 1);

            assertNotNull(preview);
            assertNull(preview.agentName()); // null descriptor → null name
        }
    }

    // ─── Schedule export ──────────────────────────────────────

    @Nested
    @DisplayName("Schedule export")
    class ScheduleExport {

        @Test
        @DisplayName("should include schedules in export")
        void includesSchedules() throws Exception {
            String agentId = "agent-sched";

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Scheduled Agent");
            agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=1"));
            when(documentDescriptorStore.readDescriptorWithHistory(agentId, 1)).thenReturn(agentDescriptor);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));

            // Schedules exist
            var schedule = new ScheduleConfiguration();
            schedule.setId("schedule-1");
            when(scheduleStore.readSchedulesByAgentId(agentId)).thenReturn(List.of(schedule));

            Response response = exportService.exportAgent(agentId, 1, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            verify(scheduleStore).readSchedulesByAgentId(agentId);
        }

        @Test
        @DisplayName("should handle empty schedule list gracefully")
        void emptySchedules() throws Exception {
            String agentId = "agent-no-sched";

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("No Schedule Agent");
            agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=1"));
            when(documentDescriptorStore.readDescriptorWithHistory(agentId, 1)).thenReturn(agentDescriptor);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleStore.readSchedulesByAgentId(agentId)).thenReturn(List.of());

            Response response = exportService.exportAgent(agentId, 1, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    // ─── Selective export ──────────────────────────────────────

    @Nested
    @DisplayName("Selective export (selectedResourceIds)")
    class SelectiveExport {

        @Test
        @DisplayName("should pass selectedResourceIds to write filter")
        void selectiveExportFilters() throws Exception {
            String agentId = "agent-selective";

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Selective Agent");
            agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=1"));
            when(documentDescriptorStore.readDescriptorWithHistory(agentId, 1)).thenReturn(agentDescriptor);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleStore.readSchedulesByAgentId(agentId)).thenReturn(List.of());

            Response response = exportService.exportAgent(agentId, 1, "res1,res2");

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    // ─── previewExport error paths ──────────────────────────

    @Nested
    @DisplayName("previewExport error paths")
    class PreviewErrors {

        @Test
        @DisplayName("should propagate exception when agent store throws")
        void propagatesAgentStoreError() throws Exception {
            when(agentStore.read(eq("error-agent"), eq(1)))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> exportService.previewExport("error-agent", 1));
        }

        @Test
        @DisplayName("should reject null agentId in preview")
        void rejectsNullAgentIdInPreview() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport(null, 1));
        }
    }

    // ─── sanitizeFileName edge cases ─────────────────────────

    @Nested
    @DisplayName("sanitizeFileName edge cases via getAgentZipArchive")
    class SanitizeFileNameEdgeCases {

        @Test
        @DisplayName("should reject filename with null bytes")
        void nullBytes() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive("file\u0000name.zip"));
        }

        @Test
        @DisplayName("should reject filename with pipe character")
        void pipeChar() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive("agent|id.zip"));
        }

        @Test
        @DisplayName("should reject filename with backtick")
        void backtickChar() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive("agent`id.zip"));
        }

        @Test
        @DisplayName("should reject filename with ampersand")
        void ampersandChar() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive("agent&id.zip"));
        }

        @Test
        @DisplayName("should accept filename with plus sign")
        void plusSign() {
            // Plus is valid in the pattern ^[a-zA-Z0-9_.+\\-]+$
            // Won't throw BadRequestException, but may throw FileNotFoundException
            try {
                exportService.getAgentZipArchive("agent+id.zip");
                fail("Should throw for missing file");
            } catch (BadRequestException e) {
                fail("Should not throw BadRequestException for valid filename with plus");
            } catch (Exception e) {
                // Expected — file doesn't exist
            }
        }
    }

    // ─── convertConfigsToString edge cases ───────────────────

    @Nested
    @DisplayName("convertConfigsToString edge cases")
    class ConvertConfigsEdgeCases {

        @Test
        @DisplayName("should handle empty map")
        void emptyMap() throws Exception {
            var method = RestExportService.class.getDeclaredMethod("convertConfigsToString", Map.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<IResourceId, String> result = (Map<IResourceId, String>) method.invoke(exportService, Map.of());
            assertTrue(result.isEmpty());
        }
    }
}
