/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore.IngestedFileStoreException;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadSourceConnectorTest {

    private static final String SOURCE_KEY = "kb:src-files";

    private InMemoryIngestedFileStore fileStore;
    private Recording context;

    @BeforeEach
    void setUp() {
        fileStore = new InMemoryIngestedFileStore();
        context = new Recording(uploadSource(), Instant.MAX);
    }

    private static IngestionSource uploadSource() {
        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    private StoredFile store(String name, String mimeType, String content) {
        return fileStore.store(SOURCE_KEY, name, mimeType, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Records what the connector hands over. */
    private static final class Recording implements DiscoveryContext {
        final List<WorkItem> items = new ArrayList<>();
        final List<SourceError> errors = new ArrayList<>();
        final List<String> failedIds = new ArrayList<>();
        final IngestionSource source;
        final Instant deadline;
        boolean cancelled;

        Recording(IngestionSource source, Instant deadline) {
            this.source = source;
            this.deadline = deadline;
        }

        @Override
        public SourceContext source() {
            return new SourceContext(source, SOURCE_KEY, "run-1", false);
        }

        @Override
        public Partition partition() {
            return Partition.IMPLICIT;
        }

        @Override
        public Optional<String> cursor() {
            return Optional.empty();
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public Optional<KnownVersion> knownVersion(String documentId) {
            return Optional.empty();
        }

        @Override
        public void accept(WorkItem item) {
            items.add(item);
        }

        @Override
        public void reportFailure(String documentId, String location, SourceError error) {
            failedIds.add(documentId);
            errors.add(error);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    @Test
    @DisplayName("serves the upload type")
    void type() {
        assertEquals(IngestionSource.TYPE_UPLOAD, new UploadSourceConnector(fileStore).type());
    }

    @Test
    @DisplayName("lists every file without reading it, versioned by the hash of its bytes")
    void listsWithoutReading() {
        StoredFile handbook = store("handbook.md", "text/markdown", "# Handbook");
        StoredFile faq = store("faq.txt", "text/plain", "Q and A");

        DiscoveryResult result = new UploadSourceConnector(fileStore).discover(context);

        assertEquals(2, context.items.size());
        WorkItem first = context.items.get(0);
        assertEquals(handbook.fileId(), first.documentId());
        assertEquals(handbook.contentHash(), first.versionToken());
        assertEquals(WorkItem.Action.UPSERT, first.action());
        assertNull(first.prefetched(), "discovery downloads nothing");
        assertEquals(faq.fileId(), context.items.get(1).documentId());

        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals(Coverage.FULL_LISTING, result.coverage());
    }

    @Test
    @DisplayName("an empty store is still a full listing — the operator deleted the files")
    void emptyIsFull() {
        DiscoveryResult result = new UploadSourceConnector(fileStore).discover(context);
        assertTrue(context.items.isEmpty());
        assertEquals(Coverage.FULL_LISTING, result.coverage());
    }

    @Test
    @DisplayName("a store that cannot be listed is a source-level failure and partial coverage")
    void unlistableStore() {
        var broken = new InMemoryIngestedFileStore() {
            @Override
            public List<StoredFile> list(String sourceKey) {
                throw new IngestedFileStoreException("the database is unwell");
            }
        };

        DiscoveryResult result = new UploadSourceConnector(broken).discover(context);

        assertEquals(StopReason.CANCELLED, result.stopReason());
        assertEquals(Coverage.PARTIAL, result.coverage());
        assertEquals(1, context.errors.size());
        assertNull(context.failedIds.get(0), "about the source, not a document");
        assertInstanceOf(SourceError.Transient.class, context.errors.get(0));
        assertTrue(context.errors.get(0).reason().contains("the database is unwell"));
    }

    @Test
    @DisplayName("stops at maxFiles, at the deadline and on cancellation, each without coverage")
    void stopsShort() {
        store("a.md", "text/markdown", "a");
        store("b.md", "text/markdown", "b");

        var limited = uploadSource();
        var upload = new IngestionSource.UploadSource();
        upload.setMaxFiles(1);
        limited.setUpload(upload);
        var limitedContext = new Recording(limited, Instant.MAX);
        DiscoveryResult atLimit = new UploadSourceConnector(fileStore).discover(limitedContext);
        assertEquals(StopReason.PAGE_LIMIT, atLimit.stopReason());
        assertEquals(Coverage.PARTIAL, atLimit.coverage());
        assertEquals(1, limitedContext.items.size());

        var late = new Recording(uploadSource(), Instant.now().minusSeconds(1));
        DiscoveryResult pastDeadline = new UploadSourceConnector(fileStore).discover(late);
        assertEquals(StopReason.TIME_LIMIT, pastDeadline.stopReason());
        assertTrue(late.items.isEmpty());

        context.cancelled = true;
        DiscoveryResult cancelled = new UploadSourceConnector(fileStore).discover(context);
        assertEquals(StopReason.CANCELLED, cancelled.stopReason());
        assertEquals(Coverage.PARTIAL, cancelled.coverage());
    }

    @Test
    @DisplayName("fetch returns the bytes as a file, titled and cited by its name")
    void fetchReadsTheFile() throws SourceException {
        StoredFile file = store("handbook.pdf", "application/pdf", "%PDF-fake");
        var connector = new UploadSourceConnector(fileStore);
        connector.discover(context);

        SourceDocument document = connector.fetch(context.items.get(0), context.source());

        assertEquals(file.fileId(), document.documentId());
        var content = assertInstanceOf(Content.FileBytes.class, document.content());
        assertArrayEquals("%PDF-fake".getBytes(StandardCharsets.UTF_8), content.bytes());
        assertEquals("application/pdf", content.mimeType());
        assertEquals(file.contentHash(), document.versionToken());
        assertNull(document.validators());
        assertFalse(document.truncated());
        assertEquals("handbook", document.title());
        assertEquals("file:handbook.pdf", document.citationUrl());
        assertEquals("handbook.pdf", document.displayName());
        assertEquals("upload", document.metadata().get(SourceMetadataKeys.PROVIDER));
    }

    @Test
    @DisplayName("a file deleted after the listing is NOT_FOUND")
    void deletedBetweenListingAndRead() {
        StoredFile file = store("gone.md", "text/markdown", "x");
        var connector = new UploadSourceConnector(fileStore);
        connector.discover(context);
        fileStore.delete(SOURCE_KEY, file.fileId());

        var e = assertThrows(SourceException.class, () -> connector.fetch(context.items.get(0), context.source()));
        assertInstanceOf(SourceError.NotFound.class, e.error());
    }

    @Test
    @DisplayName("a store failure while reading is TRANSIENT, not evidence the file is gone")
    void storeFailureIsTransient() {
        store("a.md", "text/markdown", "x");
        var broken = new InMemoryIngestedFileStore() {
            @Override
            public Optional<byte[]> load(String sourceKey, String fileId) {
                throw new IngestedFileStoreException("read timed out");
            }
        };
        var item = WorkItem.upsert("a-id", "hash", Partition.IMPLICIT, null);

        var e = assertThrows(SourceException.class,
                () -> new UploadSourceConnector(broken).fetch(item, context.source()));
        assertInstanceOf(SourceError.Transient.class, e.error());
        assertFalse(e.error().isDefinitive());
        assertEquals("read timed out", e.getMessage());
    }

    @Test
    @DisplayName("a title is the file name without its extension")
    void titles() {
        assertEquals("handbook", UploadSourceConnector.titleOf("handbook.pdf"));
        assertEquals("archive.tar", UploadSourceConnector.titleOf("archive.tar.gz"));
        assertEquals(".env", UploadSourceConnector.titleOf(".env"));
        assertEquals("README", UploadSourceConnector.titleOf("README"));
    }
}
