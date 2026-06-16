/* Copyright (C) 2012-2026 EDDI contributors */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deep coverage tests for {@link ZipResourceSource}: caching, error handling,
 * snippet parsing, close/cleanup, findSnippetsDir, and extractIdFromFilename.
 */
class ZipResourceSourceDeepCoverageTest {

    private IJsonSerialization jsonSerialization;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        jsonSerialization = mock(IJsonSerialization.class);
        tempDir = Files.createTempDirectory("zrs-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up if tempDir still exists
        if (tempDir != null && Files.exists(tempDir)) {
            deleteRecursively(tempDir);
        }
    }

    // ==================== readAgent ====================

    @Test
    @DisplayName("readAgent — no agent file → RuntimeException")
    void readAgentNoAgentFile() {
        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        assertThrows(RuntimeException.class, source::readAgent);
    }

    @Test
    @DisplayName("readAgent — caching: second call returns same object")
    void readAgentCaching() throws Exception {
        // Create a minimal agent file
        String agentId = "testAgent";
        Path agentFile = tempDir.resolve(agentId + ".agent.json");
        Files.writeString(agentFile, "{\"workflows\":[]}", StandardCharsets.UTF_8);

        AgentConfiguration agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var first = source.readAgent();
        var second = source.readAgent();

        assertSame(first, second);
    }

    // ==================== readWorkflows ====================

    @Test
    @DisplayName("readWorkflows — caching: second call returns same list")
    void readWorkflowsCaching() throws Exception {
        // Setup agent with a workflow URI pointing to a non-existent dir (will skip)
        String agentId = "ag1";
        Path agentFile = tempDir.resolve(agentId + ".agent.json");
        Files.writeString(agentFile, "{}", StandardCharsets.UTF_8);

        AgentConfiguration agentConfig = new AgentConfiguration();
        URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
        agentConfig.setWorkflows(List.of(workflowUri));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var first = source.readWorkflows();
        var second = source.readWorkflows();

        assertSame(first, second);
    }

    @Test
    @DisplayName("readWorkflows — skips null results from readSingleWorkflow (no workflow dir)")
    void readWorkflowsSkipsNull() throws Exception {
        String agentId = "ag2";
        Path agentFile = tempDir.resolve(agentId + ".agent.json");
        Files.writeString(agentFile, "{}", StandardCharsets.UTF_8);

        AgentConfiguration agentConfig = new AgentConfiguration();
        URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
        agentConfig.setWorkflows(List.of(workflowUri));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var workflows = source.readWorkflows();

        // wf1 version dir doesn't exist → readSingleWorkflow returns null → skipped
        assertTrue(workflows.isEmpty());
    }

    // ==================== readSnippets ====================

    @Test
    @DisplayName("readSnippets — caching: second call returns same list")
    void readSnippetsCaching() throws Exception {
        // No snippets dir → returns empty list, but should be cached
        String agentId = "ag3";
        Path agentFile = tempDir.resolve(agentId + ".agent.json");
        Files.writeString(agentFile, "{}", StandardCharsets.UTF_8);

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var first = source.readSnippets();
        var second = source.readSnippets();

        assertSame(first, second);
    }

    @Test
    @DisplayName("readSnippets — no snippets dir → returns empty list")
    void readSnippetsNoSnippetsDir() {
        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();
        assertTrue(snippets.isEmpty());
    }

    @Test
    @DisplayName("readSnippets — snippet with null name is skipped")
    void readSnippetsNullNameSkipped() throws Exception {
        // Create snippets dir at root level
        Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
        Path snippetFile = snippetsDir.resolve("s1.snippet.json");
        Files.writeString(snippetFile, "{\"name\": null}", StandardCharsets.UTF_8);

        PromptSnippet snippetWithNullName = new PromptSnippet();
        // name is null by default
        doReturn(snippetWithNullName).when(jsonSerialization)
                .deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        assertTrue(snippets.isEmpty());
    }

    @Test
    @DisplayName("readSnippets — deserialization failure per-snippet is caught and continues")
    void readSnippetsDeserializationFailure() throws Exception {
        Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));

        // First snippet will fail deserialization
        Path snippetFile1 = snippetsDir.resolve("bad.snippet.json");
        Files.writeString(snippetFile1, "invalid", StandardCharsets.UTF_8);

        // Second snippet succeeds
        Path snippetFile2 = snippetsDir.resolve("good.snippet.json");
        Files.writeString(snippetFile2, "{}", StandardCharsets.UTF_8);

        doThrow(new IOException("parse error"))
                .doReturn(createSnippet("good_snippet"))
                .when(jsonSerialization).deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        // Only the good snippet should be in the list
        assertEquals(1, snippets.size());
        assertEquals("good_snippet", snippets.get(0).name());
    }

    @Test
    @DisplayName("readSnippets — valid snippet is included")
    void readSnippetsValidSnippet() throws Exception {
        Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
        Path snippetFile = snippetsDir.resolve("myid.snippet.json");
        Files.writeString(snippetFile, "{}", StandardCharsets.UTF_8);

        doReturn(createSnippet("test_snippet")).when(jsonSerialization)
                .deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        assertEquals(1, snippets.size());
        assertEquals("test_snippet", snippets.get(0).name());
        assertEquals("myid", snippets.get(0).sourceId());
    }

    // ==================== close ====================

    @Test
    @DisplayName("close — deletes temp dir")
    void closeDeletesTempDir() throws Exception {
        Path subFile = Files.createFile(tempDir.resolve("dummy.txt"));
        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);

        source.close();

        assertFalse(Files.exists(tempDir));
        // Prevent tearDown from failing
        tempDir = null;
    }

    @Test
    @DisplayName("close — nonexistent rootDir → no error")
    void closeNonexistentDir() throws Exception {
        Path nonexistent = tempDir.resolve("doesnotexist");
        ZipResourceSource source = new ZipResourceSource(nonexistent, jsonSerialization);

        assertDoesNotThrow(source::close);
    }

    // ==================== findSnippetsDir ====================

    @Test
    @DisplayName("findSnippetsDir — checks root/snippets")
    void findSnippetsDirAtRoot() throws Exception {
        Path snippetsDir = Files.createDirectories(tempDir.resolve("snippets"));
        Path snippetFile = snippetsDir.resolve("s1.snippet.json");
        Files.writeString(snippetFile, "{}", StandardCharsets.UTF_8);

        doReturn(createSnippet("s1")).when(jsonSerialization)
                .deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        assertFalse(snippets.isEmpty());
    }

    @Test
    @DisplayName("findSnippetsDir — checks agent/snippets")
    void findSnippetsDirInAgent() throws Exception {
        Path agentDir = Files.createDirectories(tempDir.resolve("agentDir"));
        Path snippetsDir = Files.createDirectories(agentDir.resolve("snippets"));
        Path snippetFile = snippetsDir.resolve("s2.snippet.json");
        Files.writeString(snippetFile, "{}", StandardCharsets.UTF_8);

        doReturn(createSnippet("s2")).when(jsonSerialization)
                .deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        assertFalse(snippets.isEmpty());
    }

    @Test
    @DisplayName("findSnippetsDir — checks agent/version/snippets")
    void findSnippetsDirInVersion() throws Exception {
        Path versionDir = Files.createDirectories(tempDir.resolve("agentDir").resolve("v1"));
        Path snippetsDir = Files.createDirectories(versionDir.resolve("snippets"));
        Path snippetFile = snippetsDir.resolve("s3.snippet.json");
        Files.writeString(snippetFile, "{}", StandardCharsets.UTF_8);

        doReturn(createSnippet("s3")).when(jsonSerialization)
                .deserialize(anyString(), eq(PromptSnippet.class));

        ZipResourceSource source = new ZipResourceSource(tempDir, jsonSerialization);
        var snippets = source.readSnippets();

        assertFalse(snippets.isEmpty());
    }

    // ==================== helpers ====================

    private PromptSnippet createSnippet(String name) {
        PromptSnippet snippet = new PromptSnippet();
        snippet.setName(name);
        return snippet;
    }

    private void deleteRecursively(Path dir) throws IOException {
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
