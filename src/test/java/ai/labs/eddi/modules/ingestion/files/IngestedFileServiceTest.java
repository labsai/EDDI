/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.extract.CsvTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.ExcelTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.HtmlDocumentExtractor;
import ai.labs.eddi.modules.ingestion.extract.PdfTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PowerPointTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.WordTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accepting files for an upload source.
 *
 * <p>
 * The limits are the substance here. They exist because an upload endpoint is
 * the one place where somebody can put arbitrary bytes into a knowledge base,
 * and because the interesting cases are not "one file that is too big" but the
 * batch: twenty files that are each within the limit and together are not, and
 * a re-upload that has to free the space its predecessor occupied.
 */
@DisplayName("IngestedFileService")
class IngestedFileServiceTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private InMemoryIngestedFileStore fileStore;
    private IngestionPipeline pipeline;
    private IngestedFileService service;

    @BeforeEach
    void setUp() {
        fileStore = new InMemoryIngestedFileStore();
        pipeline = mock(IngestionPipeline.class);
        service = new IngestedFileService(fileStore, extractors(), pipeline);
    }

    private static DocumentExtractors extractors() {
        return new DocumentExtractors(List.of(new PdfTextExtractor(), new WordTextExtractor(),
                new ExcelTextExtractor(), new PowerPointTextExtractor(), new PlainTextExtractor(),
                new CsvTextExtractor(), new HtmlDocumentExtractor(new HtmlToMarkdownConverter())));
    }

    private static IngestionSource source(Integer maxFiles, Long maxFileBytes, Long maxTotalBytes) {
        var upload = new IngestionSource.UploadSource();
        upload.setMaxFiles(maxFiles);
        upload.setMaxFileBytes(maxFileBytes);
        upload.setMaxTotalBytes(maxTotalBytes);

        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        source.setUpload(upload);
        return source;
    }

    private static IngestedFileService.IncomingFile file(String name, int bytes) {
        return new IngestedFileService.IncomingFile(name, "x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("accepting a batch")
    class AcceptingABatch {

        @Test
        @DisplayName("stores what it can and names what it refused")
        void storesSomeAndRefusesOthers() {
            var outcome = service.upload(KB_ID, source(500, 1024L, 1_000_000L), List.of(
                    file("fine.txt", 10),
                    file("huge.txt", 2048),
                    file("also-fine.md", 20)));

            // A folder of thirty documents with one bad file in it must not lose the
            // other twenty-nine, or the operator has to work out which those were.
            assertEquals(List.of("fine.txt", "also-fine.md"),
                    outcome.accepted().stream().map(IIngestedFileStore.StoredFile::fileName).toList());
            assertEquals(1, outcome.rejected().size());
            assertEquals("huge.txt", outcome.rejected().getFirst().fileName());
            assertTrue(outcome.rejected().getFirst().reason().contains("limit for one file"),
                    outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("measures a batch against itself, not only against what was there")
        void measuresTheBatchAgainstItself() {
            // Each file is within its own limit; together they are not. Checking only
            // against the stored total lets a single request blow straight past it.
            var outcome = service.upload(KB_ID, source(500, 1000L, 1500L), List.of(
                    file("a.txt", 900),
                    file("b.txt", 900)));

            assertEquals(1, outcome.accepted().size());
            assertEquals(1, outcome.rejected().size());
            assertTrue(outcome.rejected().getFirst().reason().contains("total"),
                    outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("a replacement frees the space its predecessor took")
        void replacementFreesSpace() {
            var source = source(500, 1000L, 1000L);
            assertEquals(1, service.upload(KB_ID, source, List.of(file("a.txt", 900))).accepted().size());

            // Without this, correcting one large file fills the source for good and
            // the limit that stops it is one nobody can explain.
            var outcome = service.upload(KB_ID, source, List.of(file("a.txt", 950)));

            assertEquals(1, outcome.accepted().size());
            assertTrue(outcome.rejected().isEmpty(), outcome.rejected().toString());
            assertEquals(950, fileStore.usage(IngestionPipeline.stateKey(KB_ID, source)).totalBytes());
        }

        @Test
        @DisplayName("a replacement does not count against the file limit")
        void replacementDoesNotCountAgainstTheFileLimit() {
            var source = source(1, 1000L, 100_000L);
            service.upload(KB_ID, source, List.of(file("a.txt", 10)));

            var outcome = service.upload(KB_ID, source, List.of(file("a.txt", 20)));

            assertEquals(1, outcome.accepted().size());
            assertEquals(1, fileStore.usage(IngestionPipeline.stateKey(KB_ID, source)).fileCount());
        }

        @Test
        @DisplayName("refuses one file past the count limit, having stored the rest")
        void refusesPastTheFileLimit() {
            var outcome = service.upload(KB_ID, source(2, 1000L, 100_000L), List.of(
                    file("a.txt", 10), file("b.txt", 10), file("c.txt", 10)));

            assertEquals(2, outcome.accepted().size());
            assertTrue(outcome.rejected().getFirst().reason().contains("maximum of 2 files"),
                    outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("refuses a file nothing can read, with a reason worth reading")
        void refusesAnUnreadableFile() {
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(new IngestedFileService.IncomingFile("chart.png", png)));

            assertTrue(outcome.accepted().isEmpty());
            assertFalse(outcome.rejected().getFirst().reason().isBlank());
        }

        @Test
        @DisplayName("refuses an empty file rather than storing nothing under a name")
        void refusesAnEmptyFile() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(new IngestedFileService.IncomingFile("empty.txt", new byte[0])));

            assertEquals("This file is empty.", outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("stores the type it worked out, not the one the name claimed")
        void storesTheResolvedType() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(new IngestedFileService.IncomingFile("notes.csv",
                            "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8))));

            assertEquals("text/csv", outcome.accepted().getFirst().mimeType());
        }

        @Test
        @DisplayName("strips a path from a client-supplied file name")
        void stripsPaths() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(new IngestedFileService.IncomingFile("../../etc/passwd.txt",
                            "root:x".getBytes(StandardCharsets.UTF_8))));

            // Nothing here builds a filesystem path out of the name, but it is shown
            // to people and matched on, and a traversal in a list is a bad look at
            // best.
            assertEquals("passwd.txt", outcome.accepted().getFirst().fileName());
        }
    }

    @Nested
    @DisplayName("removing a file")
    class RemovingAFile {

        @Test
        @DisplayName("removes the vectors before the file")
        void removesVectorsFirst() {
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString())).thenReturn(true);

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            assertEquals(IngestedFileService.DeleteOutcome.DELETED, outcome);
            verify(pipeline).forgetDocument(eq(KB_ID), any(), any(), eq(stored.fileId()));
            assertTrue(fileStore.find(IngestionPipeline.stateKey(KB_ID, source), stored.fileId()).isEmpty());
        }

        @Test
        @DisplayName("says so when the chunks could not be removed")
        void reportsChunksLeftBehind() {
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString())).thenReturn(false);

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            // The operator asked for the content to be gone. Reporting a plain
            // success while it stays retrievable is the worst of the three answers.
            assertEquals(IngestedFileService.DeleteOutcome.DELETED_BUT_CHUNKS_REMAIN, outcome);
        }

        @Test
        @DisplayName("touches nothing for a file that is not there")
        void doesNothingForAnUnknownFile() {
            var source = source(500, 100_000L, 1_000_000L);

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, "nope");

            assertEquals(IngestedFileService.DeleteOutcome.NOT_FOUND, outcome);
            verify(pipeline, never()).forgetDocument(anyString(), any(), any(), anyString());
        }
    }
}
