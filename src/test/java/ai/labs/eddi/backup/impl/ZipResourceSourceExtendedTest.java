/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ZipResourceSource} — edge cases for missing files,
 * snippet directories, workflow parsing, close() behavior, legacy package
 * format.
 */
@DisplayName("ZipResourceSource — Extended Branch Coverage")
class ZipResourceSourceExtendedTest {

    @Mock
    private IJsonSerialization jsonSerialization;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ==================== readAgent — missing agent file ====================

    @Nested
    @DisplayName("readAgent — edge cases")
    class ReadAgentEdgeCases {

        @Test
        @DisplayName("missing agent file throws")
        void missingAgentFile() {
            var source = new ZipResourceSource(tempDir, jsonSerialization);
            assertThrows(RuntimeException.class, source::readAgent);
        }

        @Test
        @DisplayName("readAgent caches on second call")
        void cachesAgent() throws Exception {
            // Agent file must be directly under rootDir (findFileWithSuffix is
            // non-recursive)
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");
            Files.writeString(tempDir.resolve("agent123.descriptor.json"),
                    "{\"name\": \"My Agent\"}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);
            var descriptor = new ai.labs.eddi.configs.descriptors.model.DocumentDescriptor();
            descriptor.setName("My Agent");
            when(jsonSerialization.deserialize("{\"name\": \"My Agent\"}", ai.labs.eddi.configs.descriptors.model.DocumentDescriptor.class))
                    .thenReturn(descriptor);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            var first = source.readAgent();
            var second = source.readAgent();

            assertSame(first, second);
        }
    }

    // ==================== readWorkflows — no workflows ====================

    @Nested
    @DisplayName("readWorkflows — edge cases")
    class ReadWorkflowEdgeCases {

        @Test
        @DisplayName("agent with empty workflows list returns empty")
        void emptyWorkflows() throws Exception {
            // Agent file must be directly under rootDir (findFileWithSuffix is
            // non-recursive)
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            List<WorkflowSourceData> workflows = source.readWorkflows();
            assertTrue(workflows.isEmpty());
        }

        @Test
        @DisplayName("readWorkflows caches on second call")
        void cachesWorkflows() throws Exception {
            // Agent file must be directly under rootDir (findFileWithSuffix is
            // non-recursive)
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            var first = source.readWorkflows();
            var second = source.readWorkflows();

            assertSame(first, second);
        }
    }

    // ==================== readSnippets — various snippet locations
    // ====================

    @Nested
    @DisplayName("readSnippets — edge cases")
    class ReadSnippetEdgeCases {

        @Test
        @DisplayName("no snippets directory returns empty list")
        void noSnippetsDir() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            List<SnippetSourceData> snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("snippets at root/snippets level are found")
        void snippetsAtRootLevel() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            // Create snippets at root-level "snippets/" directory
            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snip1.snippet.json"), "{snippet data}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var snippet = new PromptSnippet();
            snippet.setName("greeting");
            when(jsonSerialization.deserialize("{snippet data}", PromptSnippet.class))
                    .thenReturn(snippet);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            List<SnippetSourceData> snippets = source.readSnippets();
            assertFalse(snippets.isEmpty());
            assertEquals("greeting", snippets.getFirst().name());
        }

        @Test
        @DisplayName("snippet with null name is skipped")
        void snippetWithNullName() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
            Files.writeString(snippetsDir.resolve("snip1.snippet.json"), "{snippet data}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var snippet = new PromptSnippet();
            snippet.setName(null); // null name
            when(jsonSerialization.deserialize("{snippet data}", PromptSnippet.class))
                    .thenReturn(snippet);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            List<SnippetSourceData> snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("readSnippets caches on second call")
        void cachesSnippets() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            var first = source.readSnippets();
            var second = source.readSnippets();

            assertSame(first, second);
        }
    }

    // ==================== close() ====================

    @Nested
    @DisplayName("AutoCloseable behavior")
    class CloseableTests {

        @Test
        @DisplayName("close() does not throw")
        void closeDoesNotThrow() throws Exception {
            Files.writeString(tempDir.resolve("agent123.agent.json"), "{agent config}");

            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(jsonSerialization.deserialize("{agent config}", AgentConfiguration.class))
                    .thenReturn(config);

            var source = new ZipResourceSource(tempDir, jsonSerialization);
            assertDoesNotThrow(source::close);
        }
    }
}
