package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.AgentSourceData;
import ai.labs.eddi.backup.IResourceSource.SnippetSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.IOException;
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
 * Unit tests for {@link ZipResourceSource}. Creates temporary directory
 * structures mimicking the EDDI export ZIP layout.
 * <p>
 * The rootDir for ZipResourceSource is the agent's top-level directory (e.g.,
 * the directory containing {@code agent123.agent.json}), which is the result of
 * unzipping the export ZIP. See the class Javadoc on {@link ZipResourceSource}
 * for the expected layout.
 */
class ZipResourceSourceTest {

    private Path tempDir;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("eddi-zip-test");
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    // ==================== Agent Reading ====================

    @Nested
    @DisplayName("Agent file reading")
    class AgentReading {

        @Test
        @DisplayName("should find and parse agent file in root directory")
        void readsAgentFile() throws Exception {
            // Agent file directly in rootDir (rootDir = unzipped agent dir)
            Files.writeString(tempDir.resolve("agent123.agent.json"),
                    "{\"description\":\"test\"}", StandardCharsets.UTF_8);

            var agentConfig = new AgentConfiguration();
            agentConfig.setDescription("test");
            when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                AgentSourceData agent = source.readAgent();

                assertNotNull(agent);
                assertEquals("agent123", agent.sourceId());
                assertEquals("test", agent.config().getDescription());
            }
        }

        @Test
        @DisplayName("should throw when no agent file exists")
        void throwsWhenNoAgentFile() {
            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                assertThrows(RuntimeException.class, source::readAgent);
            }
        }

        @Test
        @DisplayName("should read name from descriptor file")
        void readsNameFromDescriptor() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"),
                    "{}", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("agent123.descriptor.json"),
                    "{\"name\":\"My Agent\"}", StandardCharsets.UTF_8);

            when(jsonSerialization.deserialize(contains("name"), eq(DocumentDescriptor.class)))
                    .thenAnswer(inv -> {
                        var desc = new DocumentDescriptor();
                        desc.setName("My Agent");
                        return desc;
                    });
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(new AgentConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                AgentSourceData agent = source.readAgent();

                assertEquals("My Agent", agent.name());
            }
        }

        @Test
        @DisplayName("should cache agent data on repeated calls")
        void cachesAgentData() throws Exception {
            Files.writeString(tempDir.resolve("a1.agent.json"),
                    "{}", StandardCharsets.UTF_8);
            when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                    .thenReturn(new AgentConfiguration());

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                AgentSourceData first = source.readAgent();
                AgentSourceData second = source.readAgent();

                assertSame(first, second); // same object reference = cached
            }
        }
    }

    // ==================== Snippet Reading ====================

    @Nested
    @DisplayName("Snippet reading")
    class SnippetReading {

        @Test
        @DisplayName("should find snippets in root/snippets/ directory")
        void readsSnippetsFromRootLevel() throws Exception {
            setupAgentFile();
            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp1.snippet.json"),
                    "{\"name\":\"greeting\",\"content\":\"Hi!\"}", StandardCharsets.UTF_8);

            var snippet = new ai.labs.eddi.configs.snippets.model.PromptSnippet();
            snippet.setName("greeting");
            snippet.setContent("Hi!");
            when(jsonSerialization.deserialize(anyString(),
                    eq(ai.labs.eddi.configs.snippets.model.PromptSnippet.class)))
                    .thenReturn(snippet);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, hasSize(1));
                assertEquals("greeting", snippets.get(0).name());
                assertEquals("snp1", snippets.get(0).sourceId());
            }
        }

        @Test
        @DisplayName("should find snippets inside agent subdirectory")
        void readsSnippetsFromAgentSubdir() throws Exception {
            setupAgentFile();
            // Create snippets as a subdirectory of a child dir (simulates
            // agentDir/snippets/)
            Path agentSubDir = Files.createDirectories(tempDir.resolve("agentSubDir"));
            Path snippetsDir = Files.createDirectories(agentSubDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snp2.snippet.json"),
                    "{\"name\":\"persona\"}", StandardCharsets.UTF_8);

            var snippet = new ai.labs.eddi.configs.snippets.model.PromptSnippet();
            snippet.setName("persona");
            when(jsonSerialization.deserialize(anyString(),
                    eq(ai.labs.eddi.configs.snippets.model.PromptSnippet.class)))
                    .thenReturn(snippet);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, hasSize(1));
                assertEquals("persona", snippets.get(0).name());
            }
        }

        @Test
        @DisplayName("should return empty list when no snippets directory exists")
        void emptyWhenNoSnippetsDir() throws Exception {
            setupAgentFile();
            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<SnippetSourceData> snippets = source.readSnippets();

                assertThat(snippets, empty());
            }
        }
    }

    // ==================== Workflow Reading ====================

    @Nested
    @DisplayName("Workflow reading")
    class WorkflowReading {

        @Test
        @DisplayName("should return empty list when agent has no workflows")
        void emptyWorkflowsForAgentWithNone() throws Exception {
            setupAgentFile();
            var config = new AgentConfiguration();
            config.setWorkflows(List.of());
            // Override the mock for THIS test
            when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                    .thenReturn(config);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertThat(workflows, empty());
            }
        }
    }

    // ==================== AutoCloseable ====================

    @Nested
    @DisplayName("AutoCloseable behavior")
    class CloseableBehavior {

        @Test
        @DisplayName("close() should delete the temporary directory")
        void closeCleansUp() throws Exception {
            Path closableDir = Files.createTempDirectory("eddi-close-test");
            Files.writeString(closableDir.resolve("dummy.txt"), "content");

            var source = new ZipResourceSource(closableDir, jsonSerialization);
            assertTrue(Files.exists(closableDir));

            source.close();

            assertFalse(Files.exists(closableDir));
        }

        @Test
        @DisplayName("close() should handle already-deleted directory gracefully")
        void closeHandlesAlreadyDeleted() throws Exception {
            Path closableDir = Files.createTempDirectory("eddi-close-test2");
            deleteRecursively(closableDir);

            var source = new ZipResourceSource(closableDir, jsonSerialization);
            assertDoesNotThrow(source::close);
        }
    }

    // ==================== File Reading ====================

    @Nested
    @DisplayName("File reading preserves content")
    class FileReading {

        @Test
        @DisplayName("readFile should preserve newlines in pretty-printed JSON")
        void preservesNewlines() throws Exception {
            String prettyJson = "{\n  \"description\": \"test\",\n  \"workflows\": []\n}";
            Files.writeString(tempDir.resolve("agent1.agent.json"), prettyJson, StandardCharsets.UTF_8);

            var config = new AgentConfiguration();
            // The legacy normalizer won't alter this JSON (no eddi:// URIs),
            // so the deserialize call receives the full pretty-printed content
            when(jsonSerialization.deserialize(eq(prettyJson), eq(AgentConfiguration.class)))
                    .thenReturn(config);

            try (var source = new ZipResourceSource(tempDir, jsonSerialization)) {
                source.readAgent();
                Mockito.verify(jsonSerialization).deserialize(eq(prettyJson), eq(AgentConfiguration.class));
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
