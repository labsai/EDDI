/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.docs.DocsService;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP <em>tool</em> doc surface — what makes the long-documented
 * {@code toolsWhitelist: ["read_docs", "list_docs"]} example real. The
 * traversal guard lives in {@link DocsService} and is asserted on the REST
 * surface; here the contract is the tool texts and the role check.
 */
@DisplayName("McpDocTools")
class McpDocToolsTest {

    @TempDir
    Path tempDir;

    private DocsService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DocsService();
        Field docsPathField = DocsService.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(service, tempDir.toString());
        Files.writeString(tempDir.resolve("architecture.md"), "# Architecture\nPipeline.");
        Files.writeString(tempDir.resolve("hitl.md"), "# HITL\nGate.");
    }

    private McpDocTools toolsWithAuthDisabled() {
        return new McpDocTools(service, null, false);
    }

    @Test
    @DisplayName("list_docs returns one page name per line, sorted, suffix-stripped")
    void listDocs() {
        assertEquals("architecture\nhitl", toolsWithAuthDisabled().listDocs());
    }

    @Test
    @DisplayName("read_docs returns the markdown source")
    void readDocs() {
        assertEquals("# Architecture\nPipeline.", toolsWithAuthDisabled().readDocs("architecture"));
    }

    @Test
    @DisplayName("read_docs tells an invalid name apart from an absent page")
    void readDocsFailureTexts() {
        assertTrue(toolsWithAuthDisabled().readDocs("../secrets").startsWith("Invalid document name"));
        assertTrue(toolsWithAuthDisabled().readDocs("no-such-page").startsWith("Document not found"));
    }

    @Test
    @DisplayName("list_docs reports an absent docs directory as a deployment state, not an error")
    void listDocsAbsentDirectory() throws Exception {
        Field docsPathField = DocsService.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(service, tempDir.resolve("nowhere").toString());
        assertTrue(toolsWithAuthDisabled().listDocs().startsWith("No documentation is available"));
    }

    @Test
    @DisplayName("with auth on, any of the five doc-read roles suffices — EDDI has no role hierarchy")
    void anyDocRoleSuffices() {
        for (String role : new String[]{"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver", "eddi-viewer"}) {
            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.hasRole(role)).thenReturn(true);
            assertEquals("architecture\nhitl", new McpDocTools(service, identity, true).listDocs(),
                    "role should have sufficed: " + role);
        }
    }

    @Test
    @DisplayName("with auth on, a caller with none of the roles is refused")
    void roleLessCallerRefused() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        var tools = new McpDocTools(service, identity, true);
        assertThrows(ForbiddenException.class, tools::listDocs);
        assertThrows(ForbiddenException.class, () -> tools.readDocs("architecture"));
    }

    @Test
    @DisplayName("eddi.docs.enabled=false silences this surface like every other docs surface")
    void disabledSwitchSilencesTools() throws Exception {
        Field enabledField = DocsService.class.getDeclaredField("docsEnabled");
        enabledField.setAccessible(true);
        enabledField.set(service, false);
        assertTrue(toolsWithAuthDisabled().listDocs().startsWith("No documentation is available"));
        assertTrue(toolsWithAuthDisabled().readDocs("architecture").startsWith("Document not found"));
    }
}
