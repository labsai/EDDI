/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.InMemoryIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.extract.CsvTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.ExcelTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.OfficeFixtures;
import ai.labs.eddi.modules.ingestion.extract.HtmlDocumentExtractor;
import ai.labs.eddi.modules.ingestion.extract.PdfTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PowerPointTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.WordTextExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private InMemoryIngestionStateStore stateStore;
    private SimpleMeterRegistry meterRegistry;
    private IngestedFileService service;

    @BeforeEach
    void setUp() {
        fileStore = new InMemoryIngestedFileStore();
        pipeline = mock(IngestionPipeline.class);
        stateStore = new InMemoryIngestionStateStore();
        meterRegistry = new SimpleMeterRegistry();
        // The claim is the real one, against the real store: a bare mock answers an
        // empty Optional and every delete below would report BUSY.
        when(pipeline.claimForMaintenance(anyString(), any()))
                .thenAnswer(invocation -> stateStore.startRun(
                        IngestionPipeline.stateKey(invocation.getArgument(0), invocation.getArgument(1))));
        // And the release is real too: a mock that silently does nothing would make
        // "the claim is released even on failure" a test of the mock.
        doAnswer(invocation -> {
            String sourceKey = IngestionPipeline.stateKey(invocation.getArgument(0), invocation.getArgument(1));
            stateStore.finishRun(new IIngestionStateStore.IngestionRun(invocation.getArgument(2), sourceKey,
                    IIngestionStateStore.IngestionRun.Status.MAINTENANCE, null, Instant.now(),
                    0, 0, 0, 0, 0, 0, 0.0, null));
            return null;
        }).when(pipeline).releaseClaim(anyString(), any(), anyString());
        service = new IngestedFileService(fileStore, extractors(), pipeline, stateStore, meterRegistry);
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
        return IngestedFileService.IncomingFile.of(name, "x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
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
        @DisplayName("refuses a file over the limit on its declared size, without reading it")
        void refusesOnTheDeclaredSizeBeforeReading() {
            // The whole part used to be read into memory first, so a 60 MB file cost
            // 60 MB of heap in order to be told it was too big.
            var read = new AtomicBoolean();
            var oversized = new IngestedFileService.IncomingFile("huge.pdf", 5_000_000L, () -> {
                read.set(true);
                return new byte[5_000_000];
            });

            var outcome = service.upload(KB_ID, source(500, 1_000_000L, 100_000_000L), List.of(oversized));

            assertFalse(read.get(), "a file refused on its declared size must never be read");
            assertTrue(outcome.rejected().getFirst().reason().contains("limit for one file"),
                    outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("refuses a scanned PDF at upload, and says why")
        void refusesAScannedPdf() {
            var outcome = service.upload(KB_ID, source(500, 1_000_000L, 100_000_000L),
                    List.of(IngestedFileService.IncomingFile.of("scan.pdf", OfficeFixtures.pdf(""))));

            assertTrue(outcome.accepted().isEmpty());
            assertTrue(outcome.rejected().getFirst().reason().contains("no text layer"),
                    outcome.rejected().getFirst().reason());
            assertTrue(fileStore.list(IngestionPipeline.stateKey(KB_ID, source(500, 1_000_000L, 100_000_000L)))
                    .isEmpty(), "nothing is stored that no run could ever index");
        }

        @Test
        @DisplayName("uploads arriving together cannot take a source past its file limit")
        void concurrentUploadsRespectTheFileLimit() throws Exception {
            // The limits were read once, when each request arrived: two uploads
            // together both saw the same headroom and both filled it.
            var source = source(5, 1000L, 1_000_000L);
            int uploads = 8;
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(uploads);
            try {
                List<Future<IngestedFileService.UploadOutcome>> results = new ArrayList<>();
                for (int i = 0; i < uploads; i++) {
                    String name = "doc-" + i + ".txt";
                    results.add(pool.submit(() -> {
                        start.await();
                        return service.upload(KB_ID, source, List.of(file(name, 10)));
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (var result : results) {
                    accepted += result.get(30, TimeUnit.SECONDS).accepted().size();
                }

                assertEquals(5, accepted);
                assertEquals(5, fileStore.usage(IngestionPipeline.stateKey(KB_ID, source)).fileCount());
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a new file that finds the source full once stored is taken back out")
        void aNewFileThatOvershootsIsRemoved() {
            // Another instance can store between this one's check and its store. The
            // re-check afterwards takes a new file back out; nothing was replaced, so
            // nothing is lost by it.
            var source = source(1, 1000L, 1_000_000L);
            String sourceKey = IngestionPipeline.stateKey(KB_ID, source);
            var racingStore = new InMemoryIngestedFileStore() {
                @Override
                public IIngestedFileStore.StoredFile store(String key, String fileName, String mimeType,
                                                           byte[] content) {
                    // The other instance's file lands in the window.
                    super.store(key, "other-node.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
                    return super.store(key, fileName, mimeType, content);
                }
            };
            var racing = new IngestedFileService(racingStore, extractors(), pipeline, stateStore, meterRegistry);

            var outcome = racing.upload(KB_ID, source, List.of(file("mine.txt", 10)));

            assertTrue(outcome.accepted().isEmpty());
            assertEquals(1, racingStore.usage(sourceKey).fileCount());
            assertTrue(racingStore.find(sourceKey, IngestedFileIds.forFileName("mine.txt")).isEmpty());
        }

        @Test
        @DisplayName("the overshoot check never deletes a file another instance has since written")
        void theOvershootCheckKeepsAnotherInstancesFile() {
            // The other instance uploaded the same name in the same window, saw a
            // replacement, skipped its own check and told its caller "stored".
            // Deleting by name here took its file.
            var source = source(1, 1000L, 1_000_000L);
            String sourceKey = IngestionPipeline.stateKey(KB_ID, source);
            byte[] theirs = "their version".getBytes(StandardCharsets.UTF_8);
            var racingStore = new InMemoryIngestedFileStore() {
                @Override
                public IIngestedFileStore.StoredFile store(String key, String fileName, String mimeType,
                                                           byte[] content) {
                    IIngestedFileStore.StoredFile mine = super.store(key, fileName, mimeType, content);
                    super.store(key, fileName, mimeType, theirs);
                    super.store(key, "other-node.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
                    return mine;
                }
            };
            var racing = new IngestedFileService(racingStore, extractors(), pipeline, stateStore, meterRegistry);

            racing.upload(KB_ID, source, List.of(file("mine.txt", 10)));

            var kept = racingStore.find(sourceKey, IngestedFileIds.forFileName("mine.txt"));
            assertTrue(kept.isPresent(), "the other instance's file must survive");
        }

        @Test
        @DisplayName("refuses a file nothing can read, with a reason worth reading")
        void refusesAnUnreadableFile() {
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(IngestedFileService.IncomingFile.of("chart.png", png)));

            assertTrue(outcome.accepted().isEmpty());
            assertFalse(outcome.rejected().getFirst().reason().isBlank());
        }

        @Test
        @DisplayName("refuses an empty file rather than storing nothing under a name")
        void refusesAnEmptyFile() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(IngestedFileService.IncomingFile.of("empty.txt", new byte[0])));

            assertEquals("This file is empty.", outcome.rejected().getFirst().reason());
        }

        @Test
        @DisplayName("stores the type it worked out, not the one the name claimed")
        void storesTheResolvedType() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(IngestedFileService.IncomingFile.of("notes.csv",
                            "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8))));

            assertEquals("text/csv", outcome.accepted().getFirst().mimeType());
        }

        @Test
        @DisplayName("names a file whose bytes could not be read, and keeps the rest")
        void namesAFileThatCouldNotBeRead() {
            var unreadable = new IngestedFileService.IncomingFile("truncated.txt", () -> {
                throw new IOException("connection reset");
            });

            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(unreadable, file("fine.txt", 10)));

            // The part never arrived intact. Aborting the batch here would lose the
            // files that did, with nothing to say which those were.
            assertEquals(1, outcome.accepted().size());
            assertEquals("truncated.txt", outcome.rejected().getFirst().fileName());
        }

        @Test
        @DisplayName("reads each file only when its turn comes")
        void readsEachFileOnlyWhenItsTurnComes() {
            // The batch must not be materialised up front: a request carrying
            // several files would otherwise cost the whole request in heap, per
            // request in flight.
            List<String> readOrder = new ArrayList<>();
            List<IngestedFileService.IncomingFile> batch = List.of(
                    new IngestedFileService.IncomingFile("a.txt", () -> {
                        readOrder.add("a.txt");
                        return "a".getBytes(StandardCharsets.UTF_8);
                    }),
                    new IngestedFileService.IncomingFile("b.txt", () -> {
                        readOrder.add("b.txt");
                        // By the time this is asked for, the first file is stored.
                        assertEquals(1, fileStore.list(IngestionPipeline.stateKey(KB_ID,
                                source(500, 100_000L, 1_000_000L))).size());
                        return "b".getBytes(StandardCharsets.UTF_8);
                    }));

            service.upload(KB_ID, source(500, 100_000L, 1_000_000L), batch);

            assertEquals(List.of("a.txt", "b.txt"), readOrder);
        }

        @Test
        @DisplayName("counts what it stored and what it refused")
        void countsOutcomes() {
            service.upload(KB_ID, source(500, 1024L, 1_000_000L),
                    List.of(file("fine.txt", 10), file("huge.txt", 2048)));

            assertEquals(1.0, meterRegistry.counter("eddi.ingestion.files.stored",
                    "source", "handbooks").count());
            assertEquals(1.0, meterRegistry.counter("eddi.ingestion.files.rejected",
                    "source", "handbooks").count());
        }

        @Test
        @DisplayName("strips a path from a client-supplied file name")
        void stripsPaths() {
            var outcome = service.upload(KB_ID, source(500, 100_000L, 1_000_000L),
                    List.of(IngestedFileService.IncomingFile.of("../../etc/passwd.txt",
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
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString()))
                    .thenReturn(IngestionPipeline.ForgetOutcome.REMOVED);

            // The file must still be there when the vectors go. The other order
            // leaves content in the knowledge base that the operator can no longer
            // see, let alone delete, if the removal fails.
            AtomicBoolean fileStillPresentWhenVectorsWent = new AtomicBoolean();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString())).thenAnswer(invocation -> {
                fileStillPresentWhenVectorsWent.set(
                        fileStore.find(IngestionPipeline.stateKey(KB_ID, source), stored.fileId()).isPresent());
                return IngestionPipeline.ForgetOutcome.REMOVED;
            });

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            assertEquals(IngestedFileService.DeleteOutcome.DELETED, outcome);
            verify(pipeline).forgetDocument(eq(KB_ID), any(), any(), eq(stored.fileId()));
            assertTrue(fileStillPresentWhenVectorsWent.get(), "the vectors must go before the file");
            assertTrue(fileStore.find(IngestionPipeline.stateKey(KB_ID, source), stored.fileId()).isEmpty());
        }

        @Test
        @DisplayName("says so when the chunks could not be removed")
        void reportsChunksLeftBehind() {
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString()))
                    .thenReturn(IngestionPipeline.ForgetOutcome.UNSUPPORTED);

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            // The operator asked for the content to be gone. Reporting a plain
            // success while it stays retrievable is the worst of the three answers.
            assertEquals(IngestedFileService.DeleteOutcome.DELETED_BUT_CHUNKS_REMAIN, outcome);
        }

        @Test
        @DisplayName("keeps the file when the vector store failed to remove its chunks")
        void keepsTheFileWhenRemovalFailed() {
            // Deleting it anyway stranded the chunks for good: the document was
            // tombstoned, reconciliation passes tombstoned documents over, and no file
            // was left to delete again once the store recovered.
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString()))
                    .thenReturn(IngestionPipeline.ForgetOutcome.FAILED);

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            assertEquals(IngestedFileService.DeleteOutcome.REMOVAL_FAILED, outcome);
            assertTrue(fileStore.find(IngestionPipeline.stateKey(KB_ID, source), stored.fileId()).isPresent(),
                    "the file must stay so the delete can be tried again");
            assertTrue(stateStore.activeRun(IngestionPipeline.stateKey(KB_ID, source)).isEmpty(),
                    "the claim is released on this path too");
        }

        @Test
        @DisplayName("refuses while a run holds the source's claim")
        void refusesWhileARunHoldsTheClaim() {
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            // A run is in flight. Deleting under it would let it re-embed the file
            // and clear the tombstone the delete wrote.
            stateStore.startRun(IngestionPipeline.stateKey(KB_ID, source));

            var outcome = service.delete(KB_ID, new RagConfiguration(), source, stored.fileId());

            assertEquals(IngestedFileService.DeleteOutcome.BUSY, outcome);
            verify(pipeline, never()).forgetDocument(anyString(), any(), any(), anyString());
            assertTrue(fileStore.find(IngestionPipeline.stateKey(KB_ID, source), stored.fileId()).isPresent());
        }

        @Test
        @DisplayName("releases the claim even when the store fails, so the source is not stuck")
        void releasesTheClaimOnFailure() {
            var source = source(500, 100_000L, 1_000_000L);
            var stored = service.upload(KB_ID, source,
                    List.of(file("notes.txt", 10))).accepted().getFirst();
            when(pipeline.forgetDocument(anyString(), any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("vector store is unwell"));

            assertThrows(IllegalStateException.class,
                    () -> service.delete(KB_ID, new RagConfiguration(), source, stored.fileId()));

            // A claim nobody releases blocks the source until it is reaped: a
            // quarter of an hour of 409s for every run and every delete.
            assertTrue(stateStore.activeRun(IngestionPipeline.stateKey(KB_ID, source)).isEmpty());
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
