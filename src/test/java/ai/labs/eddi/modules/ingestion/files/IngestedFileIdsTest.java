/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes two uploads the same file.
 *
 * <p>
 * This is the identity the blob, the ingestion-state row and the vectors all
 * key on, so "the same file" has to mean the same thing in every one of them.
 * The cases that matter are the ones where two names look identical on screen
 * and differ in bytes.
 */
@DisplayName("IngestedFileIds")
class IngestedFileIdsTest {

    @Test
    @DisplayName("the same name gives the same id")
    void isStable() {
        assertEquals(IngestedFileIds.forFileName("handbook.pdf"), IngestedFileIds.forFileName("handbook.pdf"));
    }

    @Test
    @DisplayName("different names give different ids")
    void distinguishesNames() {
        assertNotEquals(IngestedFileIds.forFileName("a.pdf"), IngestedFileIds.forFileName("b.pdf"));
    }

    @Test
    @DisplayName("the same letters in two Unicode forms are one file")
    void normalisesUnicode() {
        // macOS hands over decomposed names and Windows composed ones. The operator
        // sees one résumé.pdf either way, and would be baffled by two rows whose
        // names are identical on screen.
        String composed = "r\u00e9sum\u00e9.pdf";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertNotEquals(composed, decomposed, "the two forms must really differ, or this proves nothing");

        assertEquals(IngestedFileIds.forFileName(composed), IngestedFileIds.forFileName(decomposed));
    }

    @Test
    @DisplayName("case is not an identity")
    void foldsCase() {
        // A re-upload of Handbook.pdf is a correction of handbook.pdf, not a second
        // document beside it — and on the filesystems these files come from, those
        // are the same name.
        assertEquals(IngestedFileIds.forFileName("Handbook.PDF"), IngestedFileIds.forFileName("handbook.pdf"));
    }

    @Test
    @DisplayName("a path in the name keeps only the name")
    void stripsPaths() {
        assertEquals("passwd", IngestedFileIds.sanitize("../../etc/passwd"));
        assertEquals("report.pdf", IngestedFileIds.sanitize("C:\\Users\\me\\report.pdf"));
        assertEquals("notes.txt", IngestedFileIds.sanitize("/var/tmp/notes.txt"));
    }

    @Test
    @DisplayName("a name that is only a path component becomes something nameable")
    void refusesToProduceNothing() {
        assertEquals("file", IngestedFileIds.sanitize(".."));
        assertEquals("file", IngestedFileIds.sanitize("/"));
        assertEquals("file", IngestedFileIds.sanitize("   "));
        assertEquals("file", IngestedFileIds.sanitize(null));
    }

    @Test
    @DisplayName("a newline cannot split a log line in two")
    void stripsControlCharacters() {
        // The name is client-supplied text that is written to logs and shown in a
        // list; a control character in it is somebody else deciding what those look
        // like.
        assertEquals("reportINFO fake.pdf", IngestedFileIds.sanitize("report\nINFO fake.pdf"));
    }

    @Test
    @DisplayName("a very long name is cut to something a store can hold")
    void boundsLength() {
        String absurd = "a".repeat(5000) + ".pdf";
        assertTrue(IngestedFileIds.sanitize(absurd).length() <= 255);
    }
}
