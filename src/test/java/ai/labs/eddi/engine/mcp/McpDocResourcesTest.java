/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpDocResources, focusing on path traversal prevention.
 */
class McpDocResourcesTest {

    private McpDocResources resources;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        resources = new McpDocResources();

        // Set docsPath via reflection (normally injected by CDI)
        Field docsPathField = McpDocResources.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(resources, tempDir.toString());

        // Create a test doc
        Files.writeString(tempDir.resolve("getting-started.md"), "# Getting Started\nHello!");
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
        Field docsPathField = McpDocResources.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(resources, "/nonexistent/path");

        String result = resources.listDocs();
        assertTrue(result.contains("Docs directory not found"));
    }
}
