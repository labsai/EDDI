/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.PdfSliceBuilder.SliceTooLargeException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PdfSliceBuilder}: the pages asked for and no others, the size limit
 * enforced while writing, the read bounds of every PDF path, and no work file
 * left behind by a failure.
 */
class PdfSliceBuilderTest {

    private final PdfSliceBuilder builder = new PdfSliceBuilder();

    @TempDir
    Path workDir;

    @Nested
    class Pages {

        @Test
        void sliceHoldsExactlyTheSelectedPagesInOrder() throws IOException {
            byte[] pdf = PdfFixtures.plain(8);

            Path slice = builder.slice(pdf, 3, 5, 10L * 1024 * 1024, workDir);

            try (PDDocument document = Loader.loadPDF(Files.readAllBytes(slice))) {
                assertEquals(3, document.getNumberOfPages());
                assertTrue(firstLineOf(document, 1).startsWith("Page 3 line 1:"), firstLineOf(document, 1));
                assertTrue(firstLineOf(document, 3).startsWith("Page 5 line 1:"), firstLineOf(document, 3));
            }
        }

        @Test
        void oneChapterOfAnOutlinedDocument() throws IOException {
            // Chapter 2 of four three-page chapters is pages 4-6.
            byte[] pdf = PdfFixtures.outlined(4, 3);

            Path slice = builder.slice(pdf, 4, 6, 10L * 1024 * 1024, workDir);

            try (PDDocument document = Loader.loadPDF(Files.readAllBytes(slice))) {
                assertEquals(3, document.getNumberOfPages());
                assertTrue(firstLineOf(document, 1).startsWith("Page 4 line 1:"), firstLineOf(document, 1));
            }
        }

        @Test
        void singlePage() throws IOException {
            Path slice = builder.slice(PdfFixtures.plain(4), 4, 4, 10L * 1024 * 1024, workDir);

            try (PDDocument document = Loader.loadPDF(Files.readAllBytes(slice))) {
                assertEquals(1, document.getNumberOfPages());
                assertTrue(firstLineOf(document, 1).startsWith("Page 4 line 1:"));
            }
        }

        @Test
        void aSliceIsSmallerThanTheWholeDocument() throws IOException {
            byte[] pdf = PdfFixtures.plain(40);

            Path slice = builder.slice(pdf, 1, 2, 10L * 1024 * 1024, workDir);

            long size = Files.size(slice);
            assertTrue(size < pdf.length / 4, "slice " + size + " bytes of a " + pdf.length + "-byte document");
        }

        @Test
        void pagesBeyondTheDocumentAreRefused() {
            byte[] pdf = PdfFixtures.plain(3);

            var e = assertThrows(UnreadableDocumentException.class, () -> builder.slice(pdf, 2, 4, 10L * 1024 * 1024, workDir));

            assertTrue(e.getMessage().contains("3 page"), e.getMessage());
            assertNoWorkFiles();
        }

        @Test
        void badArgumentsAreRefusedBeforeAnyWork() {
            byte[] pdf = PdfFixtures.plain(3);
            assertThrows(IllegalArgumentException.class, () -> builder.slice(pdf, 0, 1, 1000, workDir));
            assertThrows(IllegalArgumentException.class, () -> builder.slice(pdf, 3, 2, 1000, workDir));
            assertThrows(IllegalArgumentException.class, () -> builder.slice(pdf, 1, 1, 0, workDir));
            assertThrows(IllegalArgumentException.class, () -> builder.slice(pdf, 1, 1, 1000, null));
            assertThrows(UnreadableDocumentException.class, () -> builder.slice(new byte[0], 1, 1, 1000, workDir));
            assertNoWorkFiles();
        }
    }

    @Nested
    class SizeLimit {

        @Test
        void writingStopsAtTheLimitAndLeavesNoFile() {
            byte[] pdf = PdfFixtures.plain(8);

            var e = assertThrows(SliceTooLargeException.class, () -> builder.slice(pdf, 1, 8, 2_000, workDir));

            assertEquals(2_000, e.maxBytes());
            assertNoWorkFiles();
        }

        @Test
        void aSliceExactlyAtItsSizeIsKept() throws IOException {
            byte[] pdf = PdfFixtures.plain(4);
            long size = Files.size(builder.slice(pdf, 2, 3, 10L * 1024 * 1024, workDir));
            Path dir = Files.createDirectory(workDir.resolve("again"));

            Path slice = builder.slice(pdf, 2, 3, size + 512, dir);

            assertTrue(Files.size(slice) <= size + 512);
        }
    }

    @Nested
    class Bounds {

        @Test
        void aFlateBombIsRefusedByThePreScanAndLeavesNoFile() {
            var tight = new PdfSliceBuilder(new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024));

            assertThrows(UnreadableDocumentException.class,
                    () -> tight.slice(PdfFixtures.flateBomb(8 * 1024 * 1024), 1, 1, 10L * 1024 * 1024, workDir));
            assertNoWorkFiles();
        }

        @Test
        void anEncryptedDocumentIsRefused() {
            var e = assertThrows(UnreadableDocumentException.class,
                    () -> builder.slice(encrypted(), 1, 1, 10L * 1024 * 1024, workDir));

            assertTrue(e.getMessage().contains("encrypted"), e.getMessage());
            assertNoWorkFiles();
        }

        @Test
        void bytesThatAreNotAPdfAreRefused() {
            assertThrows(UnreadableDocumentException.class,
                    () -> builder.slice("not a pdf".getBytes(), 1, 1, 10L * 1024 * 1024, workDir));
            assertNoWorkFiles();
        }

        @Test
        @Timeout(30)
        void aLoopingPageTreeEnds() {
            endsWithASliceOrARefusal(PdfFixtures.loopingPageTree());
        }

        @Test
        @Timeout(60)
        void aDeepPageTreeEnds() {
            endsWithASliceOrARefusal(PdfFixtures.deepPageTree(50_000));
        }

        @Test
        @Timeout(60)
        void aDeepPageTreeWithABrokenXrefEnds() {
            endsWithASliceOrARefusal(PdfFixtures.deepPageTreeWithBrokenXref(50_000));
        }

        private void endsWithASliceOrARefusal(byte[] pdf) {
            try {
                Path slice = builder.slice(pdf, 1, 1, 10L * 1024 * 1024, workDir);
                assertTrue(Files.exists(slice));
            } catch (UnreadableDocumentException e) {
                assertNoWorkFiles();
            }
        }
    }

    @Nested
    class WorkFile {

        @Test
        void isCreatedInTheWorkDirectory() {
            Path slice = builder.slice(PdfFixtures.plain(2), 1, 1, 10L * 1024 * 1024, workDir);

            assertEquals(workDir, slice.getParent());
            assertTrue(slice.getFileName().toString().endsWith(".pdf"));
        }

        @Test
        void isReadableByItsOwnerOnly() throws IOException {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

            Path slice = builder.slice(PdfFixtures.plain(2), 1, 1, 10L * 1024 * 1024, workDir);

            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(slice));
        }
    }

    private void assertNoWorkFiles() {
        try (Stream<Path> files = Files.list(workDir)) {
            assertEquals(0, files.count(), "a failed slice left a work file behind");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String firstLineOf(PDDocument document, int page) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document).strip().lines().findFirst().orElse("");
    }

    private static byte[] encrypted() {
        try (PDDocument document = Loader.loadPDF(PdfFixtures.plain(2))) {
            // No user password: the file opens, so the builder sees it is encrypted.
            var policy = new StandardProtectionPolicy("owner", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
