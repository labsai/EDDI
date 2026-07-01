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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended branch coverage tests for {@link RestExportService} focusing on
 * sanitizeFileName, sanitizePathComponent, parseSelectedResourceIds,
 * extractReferencedSnippetNames, writeSelectedConfigs, collectSelectedConfigs,
 * and prepareZipFilename branches.
 */
@DisplayName("RestExportService — Extended Branch Coverage")
class RestExportServiceBranchTest {

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
    // sanitizeFileName — via reflection
    // =========================================================

    @Nested
    @DisplayName("sanitizeFileName")
    class SanitizeFileNameTests {

        @Test
        @DisplayName("null filename throws BadRequestException")
        void nullFilename() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class, () -> method.invoke(null, (String) null));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("empty filename throws BadRequestException")
        void emptyFilename() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class, () -> method.invoke(null, ""));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("filename with special chars throws BadRequestException")
        void specialChars() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class, () -> method.invoke(null, "file name.zip"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("valid filename passes")
        void validFilename() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(null, "agent-1_v2.zip");
            assertEquals("agent-1_v2.zip", result);
        }

        @Test
        @DisplayName("filename with plus sign passes")
        void plusSign() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(null, "My+Agent-1.zip");
            assertEquals("My+Agent-1.zip", result);
        }

        @Test
        @DisplayName("filename with path traversal throws")
        void pathTraversal() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod("sanitizeFileName", String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class, () -> method.invoke(null, "../etc/passwd"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }
    }

    // =========================================================
    // sanitizePathComponent — via reflection
    // =========================================================

    @Nested
    @DisplayName("sanitizePathComponent")
    class SanitizePathComponentTests {

        @Test
        @DisplayName("null value throws BadRequestException")
        void nullValue() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class,
                    () -> method.invoke(null, null, "paramName"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("empty value throws BadRequestException")
        void emptyValue() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class,
                    () -> method.invoke(null, "", "paramName"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("value with .. throws BadRequestException")
        void dotDot() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class,
                    () -> method.invoke(null, "..", "paramName"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("value with forward slash throws BadRequestException")
        void forwardSlash() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class,
                    () -> method.invoke(null, "a/b", "paramName"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("value with backslash throws BadRequestException")
        void backslash() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            var ex = assertThrows(Exception.class,
                    () -> method.invoke(null, "a\\b", "paramName"));
            assertTrue(ex.getCause() instanceof BadRequestException);
        }

        @Test
        @DisplayName("valid value passes")
        void validValue() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "sanitizePathComponent", String.class, String.class);
            method.setAccessible(true);
            // Should not throw
            method.invoke(null, "aabbccddeeff112233445566", "agentId");
        }
    }

    // =========================================================
    // parseSelectedResourceIds
    // =========================================================

    @Nested
    @DisplayName("parseSelectedResourceIds")
    class ParseSelectedResourceIdsTests {

        @Test
        @DisplayName("null returns null (export all)")
        void nullReturnsNull() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            Object result = method.invoke(exportService, (String) null);
            assertNull(result);
        }

        @Test
        @DisplayName("empty string returns null")
        void emptyReturnsNull() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            Object result = method.invoke(exportService, "");
            assertNull(result);
        }

        @Test
        @DisplayName("blank string returns null")
        void blankReturnsNull() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            Object result = method.invoke(exportService, "   ");
            assertNull(result);
        }

        @Test
        @DisplayName("comma-separated values are parsed and trimmed")
        @SuppressWarnings("unchecked")
        void commaSeparated() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            Set<String> result = (Set<String>) method.invoke(exportService, " r1 , r2 , r3 ");
            assertEquals(3, result.size());
            assertTrue(result.contains("r1"));
            assertTrue(result.contains("r2"));
            assertTrue(result.contains("r3"));
        }

        @Test
        @DisplayName("empty entries from trailing commas are filtered")
        @SuppressWarnings("unchecked")
        void trailingCommas() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "parseSelectedResourceIds", String.class);
            method.setAccessible(true);
            Set<String> result = (Set<String>) method.invoke(exportService, "r1,,r2,");
            assertEquals(2, result.size());
            assertTrue(result.contains("r1"));
            assertTrue(result.contains("r2"));
        }
    }

    // =========================================================
    // extractReferencedSnippetNames
    // =========================================================

    @Nested
    @DisplayName("extractReferencedSnippetNames")
    class ExtractSnippetNamesTests {

        @Test
        @DisplayName("null config strings are skipped")
        void nullConfigSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "extractReferencedSnippetNames", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) method.invoke(exportService,
                    (Object) List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("config with snippet references extracts names")
        void extractsNames() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "extractReferencedSnippetNames", List.class);
            method.setAccessible(true);
            List<String> configs = List.of("{{snippets.greeting}} and {snippets.farewell}");
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) method.invoke(exportService, (Object) configs);
            assertTrue(result.contains("greeting"));
            assertTrue(result.contains("farewell"));
        }

        @Test
        @DisplayName("empty config strings are skipped")
        void emptyConfigSkipped() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "extractReferencedSnippetNames", List.class);
            method.setAccessible(true);
            List<String> configs = new ArrayList<>();
            configs.add("");
            configs.add(null);
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) method.invoke(exportService, (Object) configs);
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================
    // getAgentZipArchive — via export path traversal guard
    // =========================================================

    @Nested
    @DisplayName("getAgentZipArchive — security")
    class GetAgentZipArchiveSecurity {

        @Test
        @DisplayName("filename with spaces throws BadRequestException")
        void filenameWithSpaces() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive("file name.zip"));
        }

        @Test
        @DisplayName("null filename throws BadRequestException")
        void nullFilename() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive(null));
        }

        @Test
        @DisplayName("empty filename throws BadRequestException")
        void emptyFilename() {
            assertThrows(BadRequestException.class,
                    () -> exportService.getAgentZipArchive(""));
        }
    }

    // =========================================================
    // exportAgent — agentId validation
    // =========================================================

    @Nested
    @DisplayName("exportAgent — agentId validation")
    class ExportAgentValidation {

        @Test
        @DisplayName("agentId with .. throws BadRequestException")
        void agentIdPathTraversal() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("../etc", 1, null));
        }

        @Test
        @DisplayName("null agentId throws BadRequestException")
        void nullAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent(null, 1, null));
        }

        @Test
        @DisplayName("agentId with slash throws BadRequestException")
        void agentIdWithSlash() {
            assertThrows(BadRequestException.class,
                    () -> exportService.exportAgent("agent/id", 1, null));
        }
    }

    // =========================================================
    // previewExport — agentId validation
    // =========================================================

    @Nested
    @DisplayName("previewExport — agentId validation")
    class PreviewExportValidation {

        @Test
        @DisplayName("agentId with .. throws BadRequestException")
        void agentIdPathTraversal() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport("..", 1));
        }

        @Test
        @DisplayName("null agentId throws BadRequestException")
        void nullAgentId() {
            assertThrows(BadRequestException.class,
                    () -> exportService.previewExport(null, 1));
        }
    }

    // =========================================================
    // collectSelectedConfigs branches
    // =========================================================

    @Nested
    @DisplayName("collectSelectedConfigs")
    class CollectSelectedConfigsTests {

        @Test
        @DisplayName("null selectedIds collects all values")
        void nullSelectedIdsCollectsAll() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "collectSelectedConfigs", List.class, Map.class, Set.class);
            method.setAccessible(true);

            List<String> target = new ArrayList<>();
            Map<IResourceId, String> configs = new LinkedHashMap<>();
            configs.put(testResourceId("r1", 1), "config1");
            configs.put(testResourceId("r2", 1), "config2");

            method.invoke(exportService, target, configs, null);

            assertEquals(2, target.size());
            assertTrue(target.contains("config1"));
            assertTrue(target.contains("config2"));
        }

        @Test
        @DisplayName("non-null selectedIds filters to selected only")
        void nonNullSelectedIdsFilters() throws Exception {
            Method method = RestExportService.class.getDeclaredMethod(
                    "collectSelectedConfigs", List.class, Map.class, Set.class);
            method.setAccessible(true);

            List<String> target = new ArrayList<>();
            Map<IResourceId, String> configs = new LinkedHashMap<>();
            configs.put(testResourceId("r1", 1), "config1");
            configs.put(testResourceId("r2", 1), "config2");

            Set<String> selectedIds = Set.of("r1");
            method.invoke(exportService, target, configs, selectedIds);

            assertEquals(1, target.size());
            assertTrue(target.contains("config1"));
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

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
