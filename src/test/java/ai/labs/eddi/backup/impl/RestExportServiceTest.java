/*
 * Copyright (c) 2016-2026 EDDI contributors
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
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestExportService} covering security validations,
 * snippet reference extraction, preview logic, export flow, schedules, and
 * helper methods.
 */
class RestExportServiceTest {

    private IDocumentDescriptorStore documentDescriptorStore;
    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
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
        var httpCallsStore = mock(IApiCallsStore.class);
        var llmStore = mock(ILlmStore.class);
        var propertySetterStore = mock(IPropertySetterStore.class);
        var outputStore = mock(IOutputStore.class);
        var mcpCallsStore = mock(IMcpCallsStore.class);
        var ragStore = mock(IRagStore.class);
        var snippetStore = mock(IPromptSnippetStore.class);
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

    // ─── getAgentZipArchive security ─────────────────────────────

    @Nested
    @DisplayName("getAgentZipArchive security validation")
    class ZipArchiveSecurity {

        @Test
        @DisplayName("should reject null filename")
        void nullFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive(null));
        }

        @Test
        @DisplayName("should reject empty filename")
        void emptyFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive(""));
        }

        @Test
        @DisplayName("should reject filename with path traversal")
        void pathTraversal() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("../etc/passwd"));
        }

        @Test
        @DisplayName("should reject filename with spaces")
        void spacesInFilename() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("agent file.zip"));
        }

        @Test
        @DisplayName("should reject filename with shell characters")
        void shellChars() {
            assertThrows(BadRequestException.class, () -> exportService.getAgentZipArchive("agent;rm -rf.zip"));
        }

        @Test
        @DisplayName("should accept valid filename with alphanumerics and valid chars")
        void validFilename() {
            // Valid pattern: alphanumeric, dots, hyphens, underscores, plus
            // Note: will throw FileNotFoundException since the file doesn't exist,
            // but should NOT throw BadRequestException (security validation passes)
            assertThrows(Exception.class, () -> exportService.getAgentZipArchive("My-Agent_v1.0+build.zip"));
            // The exception should NOT be BadRequestException
            try {
                exportService.getAgentZipArchive("valid-agent-id-1.zip");
                fail("Should throw for missing file");
            } catch (BadRequestException e) {
                fail("Should not throw BadRequestException for valid filename");
            } catch (Exception e) {
                // Expected: FileNotFoundException or similar — proves security validation
                // passed
            }
        }
    }

    // ─── exportAgent security ────────────────────────────────────

    @Nested
    @DisplayName("exportAgent path validation")
    class ExportAgentSecurity {

        @Test
        @DisplayName("should reject agentId with path traversal")
        void pathTraversalInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("../../../etc/passwd", 1, null));
        }

        @Test
        @DisplayName("should reject agentId with forward slash")
        void slashInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agents/evil", 1, null));
        }

        @Test
        @DisplayName("should reject agentId with backslash")
        void backslashInAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agents\\evil", 1, null));
        }

        @Test
        @DisplayName("should reject empty agentId")
        void emptyAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("", 1, null));
        }

        @Test
        @DisplayName("should reject null agentId")
        void nullAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent(null, 1, null));
        }
    }

    // ─── exportAgent flow ────────────────────────────────────────

    @Nested
    @DisplayName("exportAgent flow")
    class ExportAgentFlow {

        @Test
        @DisplayName("should export agent and return location URI")
        void happyPath() throws Exception {
            String agentId = "agent123";
            int agentVersion = 1;

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(agentStore.read(agentId, agentVersion)).thenReturn(agentConfig);

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Test Agent");
            agentDescriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=" + agentVersion));
            when(documentDescriptorStore.readDescriptorWithHistory(agentId, agentVersion)).thenReturn(agentDescriptor);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            when(secretScrubber.scrubJson(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleStore.readSchedulesByAgentId(agentId)).thenReturn(List.of());

            Response response = exportService.exportAgent(agentId, agentVersion, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
            assertNotNull(response.getLocation());
            assertTrue(response.getLocation().toString().contains(".zip"));
            verify(zipArchive).createZip(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("should propagate ResourceNotFoundException")
        void agentNotFound() throws Exception {
            when(agentStore.read("nonexistent", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> exportService.exportAgent("nonexistent", 1, null));
        }
    }

    // ─── previewExport ───────────────────────────────────────────

    @Nested
    @DisplayName("previewExport")
    class PreviewExport {

        @Test
        @DisplayName("should return preview with agent and workflow resources")
        void happyPath() throws Exception {
            String agentId = "agent-abc";
            int agentVersion = 1;

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=2")));

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Test Agent");

            when(agentStore.read(agentId, agentVersion)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, agentVersion)).thenReturn(agentDescriptor);

            var wfConfig = new WorkflowConfiguration();
            when(workflowStore.read("wf-1", 2)).thenReturn(wfConfig);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            var wfDescriptor = new DocumentDescriptor();
            wfDescriptor.setName("Workflow 1");
            when(documentDescriptorStore.readDescriptor("wf-1", 2)).thenReturn(wfDescriptor);

            var preview = exportService.previewExport(agentId, agentVersion);

            assertNotNull(preview);
            assertEquals(agentId, preview.agentId());
            assertEquals("Test Agent", preview.agentName());
            assertEquals(agentVersion, preview.agentVersion());
            assertFalse(preview.resources().isEmpty());
            // Should have at least agent + workflow entries
            assertTrue(preview.resources().size() >= 2);
        }

        @Test
        @DisplayName("should reject path-traversal agentId in preview")
        void previewRejectsPathTraversal() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport("../hack", 1));
        }

        @Test
        @DisplayName("should handle agent with no workflows")
        void noWorkflows() throws Exception {
            String agentId = "agent-empty";
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());

            var agentDescriptor = new DocumentDescriptor();
            agentDescriptor.setName("Empty Agent");

            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, 1)).thenReturn(agentDescriptor);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            ExportPreview preview = exportService.previewExport(agentId, 1);

            assertNotNull(preview);
            assertEquals(1, preview.resources().size());
            assertEquals("agent", preview.resources().getFirst().resourceType());
            assertTrue(preview.resources().getFirst().required());
        }

        @Test
        @DisplayName("should handle null agent descriptor name")
        void nullDescriptorName() throws Exception {
            String agentId = "agent-noname";
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());

            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(agentId, 1)).thenReturn(null);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            ExportPreview preview = exportService.previewExport(agentId, 1);

            assertNotNull(preview);
            assertNull(preview.agentName());
        }

        @Test
        @DisplayName("should assign correct workflowIndex for multiple workflows")
        void multipleWorkflowIndices() throws Exception {
            String agentId = "agent-multi";
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-a?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-b?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-c?version=1")));

            when(agentStore.read(agentId, 1)).thenReturn(agentConfig);
            when(documentDescriptorStore.readDescriptor(eq(agentId), eq(1))).thenReturn(new DocumentDescriptor());
            when(workflowStore.read(anyString(), anyInt())).thenReturn(new WorkflowConfiguration());
            when(documentDescriptorStore.readDescriptor(startsWith("wf-"), eq(1))).thenReturn(new DocumentDescriptor());
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            ExportPreview preview = exportService.previewExport(agentId, 1);

            // Agent + 3 workflows = 4 resources
            var workflows = preview.resources().stream()
                    .filter(r -> "workflow".equals(r.resourceType()))
                    .toList();
            assertEquals(3, workflows.size());
        }
    }

    // ─── parseSelectedResourceIds ────────────────────────────────

    @Nested
    @DisplayName("parseSelectedResourceIds")
    class ParseSelectedResourceIds {

        private Set<String> invokeParseSelectedResourceIds(String input) throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) method.invoke(exportService, input);
            return result;
        }

        @Test
        @DisplayName("null input returns null (export all)")
        void nullReturnsNull() throws Exception {
            assertNull(invokeParseSelectedResourceIds(null));
        }

        @Test
        @DisplayName("empty string returns null")
        void emptyReturnsNull() throws Exception {
            assertNull(invokeParseSelectedResourceIds(""));
        }

        @Test
        @DisplayName("blank string returns null")
        void blankReturnsNull() throws Exception {
            assertNull(invokeParseSelectedResourceIds("   "));
        }

        @Test
        @DisplayName("comma-separated values parsed correctly")
        void commaSeparated() throws Exception {
            Set<String> result = invokeParseSelectedResourceIds("a,b,c");
            assertNotNull(result);
            assertEquals(3, result.size());
            assertTrue(result.containsAll(Set.of("a", "b", "c")));
        }

        @Test
        @DisplayName("whitespace around values is trimmed")
        void trimmed() throws Exception {
            Set<String> result = invokeParseSelectedResourceIds(" a , b , c ");
            assertNotNull(result);
            assertTrue(result.contains("a"));
            assertTrue(result.contains("b"));
            assertTrue(result.contains("c"));
        }

        @Test
        @DisplayName("empty entries from trailing comma are filtered")
        void trailingComma() throws Exception {
            Set<String> result = invokeParseSelectedResourceIds("a,b,");
            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("preserves insertion order (LinkedHashSet)")
        void preservesOrder() throws Exception {
            Set<String> result = invokeParseSelectedResourceIds("z,a,m");
            assertNotNull(result);
            assertInstanceOf(LinkedHashSet.class, result);
            var list = new ArrayList<>(result);
            assertEquals("z", list.get(0));
            assertEquals("a", list.get(1));
            assertEquals("m", list.get(2));
        }
    }

    // ─── extractReferencedSnippetNames ───────────────────────────

    @Nested
    @DisplayName("extractReferencedSnippetNames")
    class ExtractReferencedSnippetNames {

        private Set<String> invokeExtract(List<String> configs) throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("extractReferencedSnippetNames", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) method.invoke(exportService, configs);
            return result;
        }

        @Test
        @DisplayName("extracts single snippet reference")
        void singleRef() throws Exception {
            Set<String> names = invokeExtract(List.of("Use {{snippets.cautious_mode}} here"));
            assertEquals(1, names.size());
            assertTrue(names.contains("cautious_mode"));
        }

        @Test
        @DisplayName("extracts multiple references across configs")
        void multipleRefs() throws Exception {
            Set<String> names = invokeExtract(List.of(
                    "{{snippets.intro}} text",
                    "{snippets.outro} more",
                    "{{snippets.middle}}"));
            assertEquals(3, names.size());
            assertTrue(names.containsAll(Set.of("intro", "outro", "middle")));
        }

        @Test
        @DisplayName("skips null entries")
        void nullEntries() throws Exception {
            List<String> configs = new ArrayList<>();
            configs.add(null);
            configs.add("{{snippets.valid}}");
            Set<String> names = invokeExtract(configs);
            assertEquals(1, names.size());
            assertTrue(names.contains("valid"));
        }

        @Test
        @DisplayName("skips empty entries")
        void emptyEntries() throws Exception {
            Set<String> names = invokeExtract(List.of("", "{{snippets.found}}"));
            assertEquals(1, names.size());
        }

        @Test
        @DisplayName("returns empty set when no references")
        void noRefs() throws Exception {
            Set<String> names = invokeExtract(List.of("no snippet references here"));
            assertTrue(names.isEmpty());
        }

        @Test
        @DisplayName("handles names with hyphens and digits")
        void namesWithHyphens() throws Exception {
            Set<String> names = invokeExtract(List.of("{{snippets.my-snippet-v2}}"));
            assertTrue(names.contains("my-snippet-v2"));
        }
    }

    // ─── prepareZipFilename ──────────────────────────────────────

    @Nested
    @DisplayName("prepareZipFilename")
    class PrepareZipFilename {

        private String invokePrepareZipFilename(DocumentDescriptor desc, String agentId, Integer version)
                throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "prepareZipFilename", DocumentDescriptor.class, String.class, Integer.class);
            method.setAccessible(true);
            return (String) method.invoke(exportService, desc, agentId, version);
        }

        @Test
        @DisplayName("named agent includes URL-encoded name prefix")
        void namedAgent() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setName("My Agent");
            String filename = invokePrepareZipFilename(desc, "abc123", 2);
            assertTrue(filename.contains("abc123"));
            assertTrue(filename.contains("-2.zip"));
            assertTrue(filename.contains("My+Agent-") || filename.contains("My%20Agent-"));
        }

        @Test
        @DisplayName("null name agent has no prefix")
        void nullName() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setName(null);
            String filename = invokePrepareZipFilename(desc, "abc123", 1);
            assertEquals("abc123-1.zip", filename);
        }

        @Test
        @DisplayName("empty name agent has no prefix")
        void emptyName() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setName("");
            String filename = invokePrepareZipFilename(desc, "abc123", 1);
            assertEquals("abc123-1.zip", filename);
        }
    }

    // ─── convertConfigsToString ──────────────────────────────────

    @Nested
    @DisplayName("convertConfigsToString")
    class ConvertConfigsToString {

        private Map<IResourceId, String> invokeConvert(Map<IResourceId, ?> input) throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("convertConfigsToString", Map.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<IResourceId, String> result = (Map<IResourceId, String>) method.invoke(exportService, input);
            return result;
        }

        @Test
        @DisplayName("serializes and scrubs each config")
        void serializesAndScrubs() throws Exception {
            var resId = testResourceId("r1", 1);
            Map<IResourceId, Object> input = Map.of(resId, "configObj");

            when(jsonSerialization.serialize("configObj")).thenReturn("{\"key\":\"val\"}");
            when(secretScrubber.scrubJson("{\"key\":\"val\"}")).thenReturn("{\"key\":\"REDACTED\"}");

            Map<IResourceId, String> result = invokeConvert(input);

            assertEquals(1, result.size());
            assertEquals("{\"key\":\"REDACTED\"}", result.get(resId));
            verify(secretScrubber).scrubJson("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("returns empty string on serialization failure")
        void serializationFailure() throws Exception {
            var resId = testResourceId("r1", 1);
            Map<IResourceId, Object> input = Map.of(resId, "bad");

            when(jsonSerialization.serialize("bad")).thenThrow(new IOException("fail"));

            Map<IResourceId, String> result = invokeConvert(input);

            assertEquals("", result.get(resId));
        }
    }

    // ─── SNIPPET_REF_PATTERN (via reflection or observable behavior) ───

    @Nested
    @DisplayName("Snippet reference extraction pattern")
    class SnippetRefPattern {

        /**
         * Replicates the private SNIPPET_REF_PATTERN to test regex logic.
         */
        private static final Pattern SNIPPET_REF_PATTERN = Pattern.compile("snippets\\.([a-zA-Z0-9_\\-]+)");

        @Test
        @DisplayName("should match {{snippets.name}} reference")
        void matchDoubleBrace() {
            var matcher = SNIPPET_REF_PATTERN.matcher("Use {{snippets.cautious_mode}} for safety");
            assertTrue(matcher.find());
            assertEquals("cautious_mode", matcher.group(1));
        }

        @Test
        @DisplayName("should match {snippets.name} reference")
        void matchSingleBrace() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{snippets.persona}");
            assertTrue(matcher.find());
            assertEquals("persona", matcher.group(1));
        }

        @Test
        @DisplayName("should extract multiple snippet names")
        void multipleSnippets() {
            String config = "{{snippets.intro}} and {{snippets.outro}} and {snippets.middle_part}";
            var matcher = SNIPPET_REF_PATTERN.matcher(config);
            var names = new java.util.ArrayList<String>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            assertEquals(3, names.size());
            assertTrue(names.containsAll(List.of("intro", "outro", "middle_part")));
        }

        @Test
        @DisplayName("should match names with hyphens and digits")
        void namesWithHyphensAndDigits() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{{snippets.my-snippet-v2}}");
            assertTrue(matcher.find());
            assertEquals("my-snippet-v2", matcher.group(1));
        }

        @Test
        @DisplayName("should not match without snippets prefix")
        void noMatch() {
            var matcher = SNIPPET_REF_PATTERN.matcher("{{properties.user_name}}");
            assertFalse(matcher.find());
        }
    }

    // ─── Test Helpers ────────────────────────────────────────────

    private static IResourceId testResourceId(String id, int version) {
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
}
