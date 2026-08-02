/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.docs.DocsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REST doc surface an EDDI agent actually reaches — MCP resources do not
 * reach one, because EDDI's own MCP client never calls {@code resources/read}.
 * <p>
 * The traversal guard is asserted here as well as on the MCP surface: the two
 * share {@link DocsService}, but a REST path parameter is the more exposed of
 * the two inputs and a regression that only reintroduced it on one surface
 * would otherwise pass.
 */
@DisplayName("RestDocs")
class RestDocsTest {

    private RestDocs restDocs;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        var service = new DocsService();
        Field docsPathField = DocsService.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(service, tempDir.toString());
        restDocs = new RestDocs(service);

        Files.writeString(tempDir.resolve("architecture.md"), "# Architecture\nPipeline.");
        Files.writeString(tempDir.resolve("hitl.md"), "# HITL\nGate.");
        Files.writeString(tempDir.resolve("not-markdown.txt"), "ignored");
    }

    @Test
    @DisplayName("lists the markdown pages, sorted and suffix-stripped")
    void listsMarkdownPages() {
        assertEquals(List.of("architecture", "hitl"), restDocs.listDocs());
    }

    @Test
    @DisplayName("reads a page as text/plain")
    void readsAPage() {
        var response = restDocs.readDoc("architecture");
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity().toString().contains("# Architecture"));
    }

    @Test
    @DisplayName("an absent page is a 404, not an empty 200")
    void absentPageIs404() {
        assertEquals(404, restDocs.readDoc("no-such-page").getStatus());
    }

    @Test
    @DisplayName("traversal attempts are 404 and never echo the supplied name back")
    void traversalAttemptsAre404() throws Exception {
        // The name is attacker-supplied, so it must not be reflected in the response —
        // hence a bare 404 rather than a "invalid name: ..." message.
        Files.writeString(tempDir.getParent().resolve("secret.md"), "top secret");

        for (String attempt : List.of("../secret", "../../etc/passwd", "sub/doc", "sub\\doc", "..", "")) {
            var response = restDocs.readDoc(attempt);
            assertEquals(404, response.getStatus(), "must refuse: " + attempt);
            assertNull(response.getEntity(), "must not echo the supplied name back: " + attempt);
        }
        assertEquals(404, restDocs.readDoc(null).getStatus());
    }

    @Test
    @DisplayName("a missing docs directory lists nothing rather than failing")
    void missingDirectoryListsNothing() throws Exception {
        var service = new DocsService();
        Field docsPathField = DocsService.class.getDeclaredField("docsPath");
        docsPathField.setAccessible(true);
        docsPathField.set(service, tempDir.resolve("does-not-exist").toString());

        var docs = new RestDocs(service);
        assertTrue(docs.listDocs().isEmpty());
        assertFalse(service.isAvailable());
    }
}
