/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.SnippetSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Extended unit tests for {@link ZipResourceSource} covering legacy
 * .package.json workflows, extension file reading from workflow directories,
 * deep snippet directory detection, workflow reading with extensions,
 * deserialization errors in snippets, and close() with nested directories.
 */
class ZipResourceSourceExtendedTest {

    private Path tempDir;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("eddi-zip-ext-test");
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    // ==================== Legacy .package.json Workflows ====================

    @Nested
    @DisplayName("Legacy .package.json workflow format")
    class LegacyPackageFormat {

        @Test
        @DisplayName("should read workflow from .package.json when .workflow.json is absent")
        void readsLegacyPackageFormat() throws Exception {
            // Setup agent with one workflow URI
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aa0011223344556677889900?version=1")));

            Files.writeString(tempDir.resolve("wf-legacy-agent.agent.json"), "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Create workflow version directory with legacy naming
            Path agentDir = tempDir; // rootDir IS the agent dir
            Path versionDir = Files.createDirectories(agentDir.resolve("aa0011223344556677889900").resolve("1"));
            Files.writeString(versionDir.resolve("aa0011223344556677889900.package.json"), "{\"legacy\":true}", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(contains("legacy"), eq(WorkflowConfiguration.class)))
                    .thenReturn(new WorkflowConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertEquals(1, workflows.size());
                assertEquals("aa0011223344556677889900", workflows.getFirst().sourceId());
            }
        }
    }

    // ==================== Workflow with extensions ====================

    @Nested
    @DisplayName("Workflow with extension files")
    class WorkflowWithExtensions {

        @Test
        @DisplayName("should read LLM extension file from workflow directory")
        void readsLlmExtension() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/bb0011223344556677889900?version=1")));

            Files.writeString(tempDir.resolve("bb00-agent.agent.json"), "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Create workflow directory with LLM extension file
            Path versionDir = Files.createDirectories(tempDir.resolve("bb0011223344556677889900").resolve("1"));

            // Workflow config JSON that references an LLM URI
            String wfJson = "\"eddi://ai.labs.llm/llmstore/llms/cc0011223344556677889900?version=2\"";
            Files.writeString(versionDir.resolve("bb0011223344556677889900.workflow.json"), wfJson, StandardCharsets.UTF_8);

            // LLM extension file
            String llmContent = "{\"model\":\"gpt-4\"}";
            Files.writeString(versionDir.resolve("cc0011223344556677889900.langchain.json"), llmContent, StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenReturn(new WorkflowConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertEquals(1, workflows.size());
                // Should have the LLM extension
                assertTrue(workflows.getFirst().extensions().containsKey("ai.labs.llm"));
                assertEquals("langchain", workflows.getFirst().extensions().get("ai.labs.llm").type());
                assertEquals(llmContent, workflows.getFirst().extensions().get("ai.labs.llm").contentJson());
            }
        }

        @Test
        @DisplayName("should skip missing extension file gracefully")
        void skipsMissingExtensionFile() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/dd0011223344556677889900?version=1")));

            Files.writeString(tempDir.resolve("dd00-agent.agent.json"), "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            Path versionDir = Files.createDirectories(tempDir.resolve("dd0011223344556677889900").resolve("1"));

            // Workflow JSON references an LLM, but the file doesn't exist on disk
            String wfJson = "\"eddi://ai.labs.llm/llmstore/llms/ee0011223344556677889900?version=1\"";
            Files.writeString(versionDir.resolve("dd0011223344556677889900.workflow.json"), wfJson, StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class)))
                    .thenReturn(new WorkflowConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertEquals(1, workflows.size());
                // No extension file found → extensions map is empty
                assertTrue(workflows.getFirst().extensions().isEmpty());
            }
        }
    }

    // ==================== Deep snippet directory ====================

    @Nested
    @DisplayName("Deep snippet directory detection")
    class DeepSnippetDirectory {

        @Test
        @DisplayName("should find snippets nested two levels deep")
        void findsSnippetsTwoLevelsDeep() throws Exception {
            setupAgentFile();

            // Create snippets at agentSubDir/versionDir/snippets/
            Path agentSubDir = Files.createDirectories(tempDir.resolve("agentSubDir"));
            Path innerDir = Files.createDirectories(agentSubDir.resolve("versionDir"));
            Path snippetsDir = Files.createDirectories(innerDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp-deep.snippet.json"),
                    "{\"name\":\"deep\"}", StandardCharsets.UTF_8);

            var snippet = new PromptSnippet();
            snippet.setName("deep");
            when(jsonSerialization.deserialize(anyString(), eq(PromptSnippet.class)))
                    .thenReturn(snippet);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, hasSize(1));
                assertEquals("deep", snippets.getFirst().name());
            }
        }
    }

    // ==================== Snippet deserialization errors ====================

    @Nested
    @DisplayName("Snippet deserialization errors")
    class SnippetDeserializationErrors {

        @Test
        @DisplayName("should skip snippet with null name after deserialization")
        void skipsNullNameSnippet() throws Exception {
            setupAgentFile();

            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp-nullname.snippet.json"),
                    "{}", StandardCharsets.UTF_8);

            var snippet = new PromptSnippet();
            snippet.setName(null); // name is null
            when(jsonSerialization.deserialize(anyString(), eq(PromptSnippet.class)))
                    .thenReturn(snippet);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, empty());
            }
        }

        @Test
        @DisplayName("should skip snippet when deserialization returns null")
        void skipsNullDeserialization() throws Exception {
            setupAgentFile();

            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp-null.snippet.json"),
                    "invalid", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(anyString(), eq(PromptSnippet.class)))
                    .thenReturn(null);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, empty());
            }
        }

        @Test
        @DisplayName("should skip snippet when deserialization throws exception")
        void skipsDeserializationError() throws Exception {
            setupAgentFile();

            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp-err.snippet.json"),
                    "corrupt", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(anyString(), eq(PromptSnippet.class)))
                    .thenThrow(new RuntimeException("Parse error"));

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, empty());
            }
        }
    }

    // ==================== Snippet caching ====================

    @Nested
    @DisplayName("Snippet caching")
    class SnippetCaching {

        @Test
        @DisplayName("should cache snippets on repeated calls")
        void cachesSnippets() throws Exception {
            setupAgentFile();

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> first = source.readSnippets();
                List<SnippetSourceData> second = source.readSnippets();

                assertSame(first, second);
            }
        }
    }

    // ==================== close() with nested directories ====================

    @Nested
    @DisplayName("close() with nested directories")
    class CloseWithNestedDirs {

        @Test
        @DisplayName("should recursively delete nested directory structure")
        void recursiveDelete() throws Exception {
            Path closeDir = Files.createTempDirectory("eddi-close-nested");
            Path subDir = Files.createDirectories(closeDir.resolve("sub1").resolve("sub2"));
            Files.writeString(subDir.resolve("file.txt"), "content", StandardCharsets.UTF_8);
            Files.writeString(closeDir.resolve("root.txt"), "root", StandardCharsets.UTF_8);

            assertTrue(Files.exists(subDir.resolve("file.txt")));

            var source = new ZipResourceSource(closeDir, jsonSerialization);
            source.close();

            assertFalse(Files.exists(closeDir));
        }
    }

    // ==================== Workflow reading with invalid URI ====================

    @Nested
    @DisplayName("Workflow reading with invalid URI")
    class WorkflowInvalidUri {

        @Test
        @DisplayName("should skip workflow with invalid URI")
        void skipsInvalidUri() throws Exception {
            var agentConfig = new AgentConfiguration();
            // URI that can't be parsed into a resource ID (no version query param)
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/bad-uri")));

            Files.writeString(tempDir.resolve("bad-uri-agent.agent.json"), "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertTrue(workflows.isEmpty());
            }
        }
    }

    // ==================== Workflow caching ====================

    @Nested
    @DisplayName("Workflow caching")
    class WorkflowCaching {

        @Test
        @DisplayName("should cache workflows on repeated calls")
        void cachesWorkflows() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            Files.writeString(tempDir.resolve("cache-agent.agent.json"), "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> first = source.readWorkflows();
                List<WorkflowSourceData> second = source.readWorkflows();

                assertSame(first, second);
            }
        }
    }

    // ==================== Descriptor name reading ====================

    @Nested
    @DisplayName("Descriptor name reading")
    class DescriptorNameReading {

        @Test
        @DisplayName("should return null name when no descriptor file exists")
        void noDescriptorFile() throws Exception {
            Files.writeString(tempDir.resolve("no-desc.agent.json"), "{}", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(new AgentConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                var agent = source.readAgent();

                assertNull(agent.name());
            }
        }

        @Test
        @DisplayName("should read descriptor from companion file")
        void readsDescriptorFromFile() throws Exception {
            Files.writeString(tempDir.resolve("has-desc.agent.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("has-desc.descriptor.json"),
                    "{\"name\":\"Named Agent\"}", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(new AgentConfiguration());

            var desc = new DocumentDescriptor();
            desc.setName("Named Agent");
            when(jsonSerialization.deserialize(contains("Named"), eq(DocumentDescriptor.class)))
                    .thenReturn(desc);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                var agent = source.readAgent();

                assertEquals("Named Agent", agent.name());
            }
        }
    }

    // ==================== Helpers ====================

    private void setupAgentFile() throws IOException {
        Files.writeString(tempDir.resolve("testAgent.agent.json"),
                "{}", StandardCharsets.UTF_8);
        when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                .thenReturn(new AgentConfiguration());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
