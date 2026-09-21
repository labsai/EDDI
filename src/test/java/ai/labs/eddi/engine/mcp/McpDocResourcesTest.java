/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.docs.DocsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpDocResources, focusing on path traversal prevention.
 * <p>
 * Every assertion here predates the extraction of {@link DocsService} and is
 * kept verbatim: this class is the evidence that moving the filesystem access
 * out did not change a single response an MCP client sees.
 */
class McpDocResourcesTest {

    private McpDocResources resources;
    private DocsService docsService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        docsService = docsServiceFor(tempDir.toString());
        resources = new McpDocResources(docsService);

        // Create a test doc
        Files.writeString(tempDir.resolve("getting-started.md"), "# Getting Started\nHello!");
    }

    /** DocsService with docsPath set directly (normally injected by CDI). */
    private static DocsService docsServiceFor(String path) throws Exception {
        var service = new DocsService();
        Field docsPathField = DocsService.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(service, path);
        return service;
    }

    @Test
    void readDoc_validName_returnsContent() {
        String result = resources.readDoc("getting-started");
        assertTrue(result.contains("# Getting Started"));
    }

    @Test
    void readDoc_notFound_returnsNotFound() {
        String result = resources.readDoc("nonexistent");
        assertTrue(result.contains("Document not found"));
    }

    // --- Path traversal prevention ---

    @Test
    void readDoc_dotDot_blocked() {
        String result = resources.readDoc("../../etc/passwd");
        assertTrue(result.contains("Invalid document name"), "Should block '..' sequences");
    }

    @Test
    void readDoc_forwardSlash_blocked() {
        String result = resources.readDoc("sub/doc");
        assertTrue(result.contains("Invalid document name"), "Should block forward slashes");
    }

    @Test
    void readDoc_backslash_blocked() {
        String result = resources.readDoc("sub\\doc");
        assertTrue(result.contains("Invalid document name"), "Should block backslashes");
    }

    @Test
    void readDoc_parentRef_blocked() {
        String result = resources.readDoc("..");
        assertTrue(result.contains("Invalid document name"), "Should block bare '..'");
    }

    @Test
    void readDoc_null_blocked() {
        String result = resources.readDoc(null);
        assertTrue(result.contains("Invalid document name"), "Should block null names");
    }

    @Test
    void readDoc_empty_blocked() {
        String result = resources.readDoc("");
        assertTrue(result.contains("Invalid document name"), "Should block empty names");
    }

    @Test
    void readDoc_dotDotSlash_blocked() {
        String result = resources.readDoc("../application.properties");
        assertTrue(result.contains("Invalid document name"));
    }

    // --- Index listing ---

    @Test
    void listDocs_returnsIndex() {
        String result = resources.listDocs();
        assertTrue(result.contains("getting-started"));
        assertTrue(result.contains("Available documents (1)"));
    }

    @Test
    void listDocs_invalidDir_returnsError() throws Exception {
        // A mis-set eddi.docs.path must still read as a misconfiguration, not as an
        // index of zero documents — which would look like success.
        var broken = new McpDocResources(docsServiceFor("/nonexistent/path"));

        String result = broken.listDocs();
        assertTrue(result.contains("Docs directory not found"));
    }
}
