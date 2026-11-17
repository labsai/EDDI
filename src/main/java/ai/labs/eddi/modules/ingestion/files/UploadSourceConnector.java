/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.SourceConnector;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code upload} source: files an operator uploaded, held by EDDI.
 *
 * <p>
 * Discovery style C (listing only), and an authoritative one: the store is the
 * whole source, so a complete listing is {@link Coverage#FULL_LISTING} — even
 * an empty one, because an operator who deleted the last file means it. Each
 * file's version token is the hash of its bytes, known without reading them, so
 * an unchanged 20 MB manual costs one metadata query per run rather than a
 * download and a full PDF parse.
 */
@ApplicationScoped
public class UploadSourceConnector implements SourceConnector {

    static final String HINT_FILE_NAME = "fileName";
    static final String HINT_MIME_TYPE = "mimeType";

    private final IIngestedFileStore fileStore;

    @Inject
    public UploadSourceConnector(IIngestedFileStore fileStore) {
        this.fileStore = fileStore;
    }

    @Override
    public String type() {
        return IngestionSource.TYPE_UPLOAD;
    }

    @Override
    public DiscoveryResult discover(DiscoveryContext context) {
        String sourceKey = context.source().sourceKey();
        IngestionSource source = context.source().source();
        Instant deadline = context.deadline();
        List<StoredFile> files;
        try {
            files = fileStore.list(sourceKey);
        } catch (RuntimeException e) {
            // The store is unavailable, so this run learned nothing about which files
            // exist. Reported as an uncovered run: concluding "no files" here would
            // delete the whole knowledge base over a database blip.
            context.reportFailure(null, null, new SourceError.Transient(
                    "The uploaded files of this source could not be listed: " + describe(e)));
            return DiscoveryResult.of(StopReason.CANCELLED, Coverage.PARTIAL);
        }

        StopReason stopReason = StopReason.COMPLETED;
        int read = 0;
        for (StoredFile file : files) {
            if (context.isCancelled()) {
                stopReason = StopReason.CANCELLED;
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                stopReason = StopReason.TIME_LIMIT;
                break;
            }
            if (read >= source.upload().maxFilesOrDefault()) {
                // The limit was lowered after the files were uploaded. Stopping short
                // is not coverage, so nothing is concluded to be deleted.
                stopReason = StopReason.PAGE_LIMIT;
                break;
            }
            read++;
            context.accept(WorkItem.upsert(file.fileId(), file.contentHash(), context.partition(), hintsOf(file)));
        }
        return DiscoveryResult.of(stopReason,
                stopReason == StopReason.COMPLETED ? Coverage.FULL_LISTING : Coverage.PARTIAL);
    }

    @Override
    public SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException {
        byte[] content;
        try {
            content = fileStore.load(context.sourceKey(), item.documentId()).orElse(null);
        } catch (IngestedFileStoreException e) {
            // The bytes could not be fetched this time. Not evidence that the file is
            // gone: the listing just said it is there.
            throw new SourceException(new SourceError.Transient(describe(e)), e);
        }
        if (content == null) {
            // Deleted between the listing and the read. The next run sees it gone
            // and reconciles it properly.
            throw new SourceException(new SourceError.NotFound("The file was deleted while the run was reading"));
        }
        String fileName = item.hints().getOrDefault(HINT_FILE_NAME, item.documentId());
        String mimeType = item.hints().get(HINT_MIME_TYPE);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SourceMetadataKeys.TITLE, titleOf(fileName));
        metadata.put(SourceMetadataKeys.CITATION_URL, "file:" + fileName);
        metadata.put(SourceMetadataKeys.PATH, fileName);
        metadata.put(SourceMetadataKeys.MIME_TYPE, mimeType);
        metadata.put(SourceMetadataKeys.PROVIDER, IngestionSource.TYPE_UPLOAD);
        // The version token is the hash of the bytes, not of the extracted text: the
        // text is re-derived on every run, and an extractor improving its output
        // would otherwise re-embed every file in the knowledge base.
        return new SourceDocument(item.documentId(), new Content.FileBytes(content, mimeType), item.versionToken(),
                null, false, metadata);
    }

    /** A file name without its extension reads better as a document title. */
    public static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem.isBlank() ? fileName : stem;
    }

    private static Map<String, String> hintsOf(StoredFile file) {
        Map<String, String> hints = new HashMap<>();
        if (file.fileName() != null) {
            hints.put(HINT_FILE_NAME, file.fileName());
        }
        if (file.mimeType() != null) {
            hints.put(HINT_MIME_TYPE, file.mimeType());
        }
        return hints;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
