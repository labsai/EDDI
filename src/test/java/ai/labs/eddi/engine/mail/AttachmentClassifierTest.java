/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which mail attachments are treated as executables or archives — by name, by
 * declared type, and by their first bytes whatever they claim to be.
 */
class AttachmentClassifierTest {

    private static final byte[] PDF = "%PDF-1.7".getBytes();

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"setup.exe", "INVOICE.EXE", "invoice.exe.", "invoice.exe . ", " run.ps1 ", "macro.xlsm", "link.lnk"})
    void executableNamesAreRecognised(String name) {
        assertTrue(AttachmentClassifier.isExecutable(name, "application/octet-stream", PDF));
    }

    @Test
    void executableTypesAreRecognisedWhateverTheName() {
        assertTrue(AttachmentClassifier.isExecutable("readme", "application/x-msdownload", PDF));
        assertTrue(AttachmentClassifier.isExecutable(null, "text/javascript", null));
        assertFalse(AttachmentClassifier.isExecutable("report.pdf", "application/pdf", PDF));
        assertFalse(AttachmentClassifier.isExecutable("report", null, PDF), "no extension, no type, harmless bytes");
    }

    @Test
    void executableMagicIsRecognisedWhateverTheNameAndType() {
        assertTrue(AttachmentClassifier.isExecutable("photo.jpg", "image/jpeg", "MZ\u0090\u0000".getBytes()));
        assertTrue(AttachmentClassifier.hasExecutableMagic("#!/bin/sh".getBytes()));
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("7f454c4602")), "ELF");
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("feedface")), "Mach-O 32");
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("feedfacf")), "Mach-O 64");
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("cefaedfe")), "Mach-O 32, reversed");
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("cffaedfe")), "Mach-O 64, reversed");
        assertTrue(AttachmentClassifier.hasExecutableMagic(hex("cafebabe")), "fat binary or class file");
    }

    @Test
    void shortOrHarmlessHeadsAreNotExecutable() {
        assertFalse(AttachmentClassifier.hasExecutableMagic(null));
        assertFalse(AttachmentClassifier.hasExecutableMagic(new byte[]{'M'}));
        assertFalse(AttachmentClassifier.hasExecutableMagic("Mx".getBytes()), "an M alone is not PE");
        assertFalse(AttachmentClassifier.hasExecutableMagic("#x".getBytes()), "a hash alone is not a script");
        assertFalse(AttachmentClassifier.hasExecutableMagic(hex("7f45")), "too short for a four-byte magic");
        assertFalse(AttachmentClassifier.hasExecutableMagic(PDF));
    }

    @Test
    void archivesAreRecognisedByNameOrType() {
        assertTrue(AttachmentClassifier.isArchive("backup.tar.gz", null));
        assertTrue(AttachmentClassifier.isArchive("disk.ISO", "application/octet-stream"));
        assertTrue(AttachmentClassifier.isArchive("download", "application/zip"));
        assertFalse(AttachmentClassifier.isArchive("notes.txt", "text/plain"));
        assertFalse(AttachmentClassifier.isArchive(null, null));
        assertFalse(AttachmentClassifier.isArchive("...", null), "only dots is no extension");
    }
}
