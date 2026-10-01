/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every {@link IIngestedFileStore} backend must share.
 *
 * <p>
 * Run against MongoDB, PostgreSQL and the in-memory double, for the same reason
 * the ingestion-state stores are: a knowledge base that loses a file on one
 * database and keeps it on another is a support problem with no error message
 * attached to it. The replacement rule in particular is easy to get subtly
 * different — an upsert on one side, an insert-then-delete on the other — and
 * the difference only shows when somebody re-uploads a corrected document.
 */
public interface IngestedFileStoreContract {

    /** A store holding nothing for the keys this contract uses. */
    IIngestedFileStore store();

    String SOURCE = "kb-1:src-1";
    String OTHER_SOURCE = "kb-1:src-2";

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("stores a file and reads it back")
    default void storesAndReads() {
        var stored = store().store(SOURCE, "handbook.pdf", "application/pdf", bytes("hello"));

        assertEquals("handbook.pdf", stored.fileName());
        assertEquals("application/pdf", stored.mimeType());
        assertEquals(5, stored.sizeBytes());
        assertArrayEquals(bytes("hello"), store().load(SOURCE, stored.fileId()).orElseThrow());
    }

    @Test
    @DisplayName("gives an absent result rather than failing for a file that is not there")
    default void missingFileIsEmpty() {
        assertTrue(store().load(SOURCE, "nothing").isEmpty());
        assertTrue(store().find(SOURCE, "nothing").isEmpty());
        assertFalse(store().delete(SOURCE, "nothing"));
    }

    @Test
    @DisplayName("a second upload of the same name replaces the first")
    default void replacesByName() {
        var first = store().store(SOURCE, "handbook.pdf", "application/pdf", bytes("old"));
        var second = store().store(SOURCE, "handbook.pdf", "application/pdf", bytes("new version"));

        // The same id, so the ingestion state and the vectors keyed by it are
        // replaced too. A generated id would leave the old handbook retrievable
        // beside the new one with nothing to say which is current.
        assertEquals(first.fileId(), second.fileId());
        assertEquals(1, store().list(SOURCE).size());
        assertArrayEquals(bytes("new version"), store().load(SOURCE, second.fileId()).orElseThrow());
        assertNotEquals(first.contentHash(), second.contentHash());
    }

    @Test
    @DisplayName("keeps each source's files to itself")
    default void scopesToTheSource() {
        var mine = store().store(SOURCE, "notes.txt", "text/plain", bytes("mine"));
        store().store(OTHER_SOURCE, "notes.txt", "text/plain", bytes("theirs"));

        assertEquals(1, store().list(SOURCE).size());
        assertArrayEquals(bytes("mine"), store().load(SOURCE, mine.fileId()).orElseThrow());
        // Same name, same derived id, different source: reading across the two
        // would let one knowledge base answer from another's documents.
        assertArrayEquals(bytes("theirs"), store().load(OTHER_SOURCE, mine.fileId()).orElseThrow());
    }

    @Test
    @DisplayName("lists files in upload order")
    default void listsInUploadOrder() {
        store().store(SOURCE, "a.txt", "text/plain", bytes("1"));
        store().store(SOURCE, "b.txt", "text/plain", bytes("2"));
        store().store(SOURCE, "c.txt", "text/plain", bytes("3"));

        assertEquals(List.of("a.txt", "b.txt", "c.txt"),
                store().list(SOURCE).stream().map(IIngestedFileStore.StoredFile::fileName).toList());
    }

    @Test
    @DisplayName("deletes one file")
    default void deletesOne() {
        var kept = store().store(SOURCE, "keep.txt", "text/plain", bytes("keep"));
        var gone = store().store(SOURCE, "drop.txt", "text/plain", bytes("drop"));

        assertTrue(store().delete(SOURCE, gone.fileId()));

        assertTrue(store().load(SOURCE, gone.fileId()).isEmpty());
        assertEquals(List.of(kept.fileId()),
                store().list(SOURCE).stream().map(IIngestedFileStore.StoredFile::fileId).toList());
    }

    @Test
    @DisplayName("deletes a whole source without touching another")
    default void deletesAll() {
        store().store(SOURCE, "a.txt", "text/plain", bytes("1"));
        store().store(SOURCE, "b.txt", "text/plain", bytes("2"));
        store().store(OTHER_SOURCE, "c.txt", "text/plain", bytes("3"));

        assertEquals(2, store().deleteAll(SOURCE));

        assertTrue(store().list(SOURCE).isEmpty());
        assertEquals(1, store().list(OTHER_SOURCE).size());
    }

    @Test
    @DisplayName("measures what a source holds")
    default void measuresUsage() {
        assertEquals(IIngestedFileStore.Usage.empty(), store().usage(SOURCE));

        store().store(SOURCE, "a.txt", "text/plain", bytes("12345"));
        store().store(SOURCE, "b.txt", "text/plain", bytes("123"));

        var usage = store().usage(SOURCE);
        assertEquals(2, usage.fileCount());
        assertEquals(8, usage.totalBytes());
    }

    @Test
    @DisplayName("counts a replacement once, at its new size")
    default void usageFollowsReplacement() {
        store().store(SOURCE, "a.txt", "text/plain", bytes("12345"));
        store().store(SOURCE, "a.txt", "text/plain", bytes("1"));

        // Otherwise a source fills up by re-uploading one corrected file, and the
        // limit that stops it is one nobody can explain.
        var usage = store().usage(SOURCE);
        assertEquals(1, usage.fileCount());
        assertEquals(1, usage.totalBytes());
    }

    @Test
    @DisplayName("hashes the bytes, so an unchanged re-upload is recognisable")
    default void hashesContent() {
        var first = store().store(SOURCE, "a.txt", "text/plain", bytes("same"));
        var again = store().store(SOURCE, "a.txt", "text/plain", bytes("same"));

        // This hash is what decides whether a run re-embeds the file. Were it to
        // change on every upload, every run after one would re-embed everything.
        assertEquals(first.contentHash(), again.contentHash());
    }
}
