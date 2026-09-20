/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Accepting, listing and removing the files of an upload source.
 *
 * <p>
 * The limits are enforced here rather than in the REST layer because they are
 * about the source, not about the request: a batch of twenty files has to be
 * measured against what the source already holds and against the files earlier
 * in the same batch, which a per-request check cannot see.
 */
@ApplicationScoped
public class IngestedFileService {

    private static final Logger LOGGER = Logger.getLogger(IngestedFileService.class);

    /**
     * How many document-state rows to read when listing a source's files. Above the
     * largest file count a source can hold, so the status of every file it has is
     * covered.
     */
    private static final int MAX_DOCUMENT_STATES = 10_000;

    private final IIngestedFileStore fileStore;
    private final DocumentExtractors extractors;
    private final IngestionPipeline pipeline;
    private final IIngestionStateStore stateStore;
    private final MeterRegistry meterRegistry;

    @Inject
    public IngestedFileService(IIngestedFileStore fileStore, DocumentExtractors extractors,
            IngestionPipeline pipeline, IIngestionStateStore stateStore, MeterRegistry meterRegistry) {
        this.fileStore = fileStore;
        this.extractors = extractors;
        this.pipeline = pipeline;
        this.stateStore = stateStore;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Stores what it can and reports what it could not, rather than refusing the
     * whole batch.
     *
     * <p>
     * Somebody who drags in a folder of thirty documents, one of which is a
     * scanned-image PDF, should end up with twenty-nine stored files and one line
     * saying which failed — not with nothing and a single error about a file they
     * would then have to find.
     */
    public UploadOutcome upload(String ragConfigId, IngestionSource source, List<IncomingFile> incoming) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        IngestionSource.UploadSource limits = source.upload();

        // What each name already occupies, so replacing a 10 MB file with a 1 MB one
        // frees space rather than counting both against the source's total. Kept up
        // to date as the batch proceeds: twenty files have to be measured against
        // each other, not only against what was there when the request arrived.
        //
        // One listing, not a listing plus a usage query: the totals are the listing
        // summed, and two reads can disagree with each other.
        Map<String, Long> sizeByFileId = new HashMap<>();
        for (IIngestedFileStore.StoredFile stored : fileStore.list(sourceKey)) {
            sizeByFileId.put(stored.fileId(), stored.sizeBytes());
        }
        long usedBytes = sizeByFileId.values().stream().mapToLong(Long::longValue).sum();
        int usedFiles = sizeByFileId.size();

        List<IIngestedFileStore.StoredFile> accepted = new ArrayList<>();
        List<RejectedFile> rejected = new ArrayList<>();

        for (IncomingFile file : incoming) {
            String fileName = IngestedFileIds.sanitize(file.fileName());
            String fileId = IngestedFileIds.forFileName(fileName);

            byte[] content;
            try {
                // Read here, one file at a time, so a batch holds one file's bytes
                // rather than the whole request's. Reading them all up front made
                // the peak cost of an upload the size of the request, per
                // concurrent request.
                content = file.content().read();
            } catch (IOException e) {
                LOGGER.warnf(e, "An uploaded part of source '%s' could not be read",
                        LogSanitizer.sanitize(source.getName()));
                rejected.add(new RejectedFile(fileName, "This file could not be read from the request."));
                continue;
            }

            Long replacedBytes = sizeByFileId.get(fileId);
            boolean isReplacement = replacedBytes != null;
            long otherBytes = usedBytes - (isReplacement ? replacedBytes : 0L);

            String refusal = refuse(fileName, content, limits, usedFiles, isReplacement, otherBytes);
            if (refusal != null) {
                rejected.add(new RejectedFile(fileName, refusal));
                continue;
            }

            String mimeType;
            try {
                mimeType = extractors.resolveMimeType(fileName, content);
                // Resolved and then checked: a type this build can name but has no
                // extractor for would otherwise be stored and fail on every run.
                if (extractors.extractorFor(mimeType).isEmpty()) {
                    throw new UnreadableDocumentException("Files of type " + mimeType + " cannot be ingested.");
                }
            } catch (UnreadableDocumentException e) {
                rejected.add(new RejectedFile(fileName, e.getMessage()));
                continue;
            }

            try {
                accepted.add(fileStore.store(sourceKey, fileName, mimeType, content));
            } catch (IIngestedFileStore.IngestedFileStoreException e) {
                LOGGER.errorf(e, "Could not store an uploaded file for source '%s'",
                        LogSanitizer.sanitize(source.getName()));
                rejected.add(new RejectedFile(fileName, "This file could not be stored. Try again."));
                continue;
            }

            usedBytes = otherBytes + content.length;
            if (!isReplacement) {
                usedFiles++;
            }
            sizeByFileId.put(fileId, (long) content.length);
        }

        // Tagged by source: a global counter says something is being refused but
        // not which knowledge base, which is the part an operator needs.
        Tags tags = Tags.of("source", String.valueOf(source.getName()));
        meterRegistry.counter("eddi.ingestion.files.stored", tags).increment(accepted.size());
        meterRegistry.counter("eddi.ingestion.files.rejected", tags).increment(rejected.size());
        return new UploadOutcome(accepted, rejected);
    }

    /** The refusal for this file, or null when it may be stored. */
    private static String refuse(String fileName, byte[] content, IngestionSource.UploadSource limits,
                                 int usedFiles, boolean isReplacement, long otherBytes) {

        if (content == null || content.length == 0) {
            return "This file is empty.";
        }
        if (content.length > limits.maxFileBytesOrDefault()) {
            return "This file is " + megabytes(content.length) + " MB. The limit for one file is "
                    + megabytes(limits.maxFileBytesOrDefault()) + " MB.";
        }
        if (!isReplacement && usedFiles >= limits.maxFilesOrDefault()) {
            return "This source already holds its maximum of " + limits.maxFilesOrDefault()
                    + " files. Delete some, or raise the limit in the source's settings.";
        }
        if (otherBytes + content.length > limits.maxTotalBytesOrDefault()) {
            return "This source would exceed its total of " + megabytes(limits.maxTotalBytesOrDefault())
                    + " MB. Delete some files, or raise the limit in the source's settings.";
        }
        return null;
    }

    /**
     * Rounded up, so a file just over the limit never reads as exactly the limit.
     */
    private static long megabytes(long bytes) {
        return Math.max(1, (bytes + 1024 * 1024 - 1) / (1024 * 1024));
    }

    public List<IIngestedFileStore.StoredFile> list(String ragConfigId, IngestionSource source) {
        return fileStore.list(IngestionPipeline.stateKey(ragConfigId, source));
    }

    public Optional<IIngestedFileStore.StoredFile> find(String ragConfigId, IngestionSource source, String fileId) {
        return fileStore.find(IngestionPipeline.stateKey(ragConfigId, source), fileId);
    }

    /**
     * Removes a file and the vectors it produced.
     *
     * @return what happened, so the caller can tell "no such file" from "removed,
     *         but its chunks are still retrievable because this vector store cannot
     *         delete them"
     */
    public DeleteOutcome delete(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                                String fileId) {

        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        if (fileStore.find(sourceKey, fileId).isEmpty()) {
            return DeleteOutcome.NOT_FOUND;
        }

        // Under the source's run claim, not merely after checking for a run. A run
        // that starts between a check and the delete lists the file, loads the bytes
        // that are about to go, embeds them and records the document as ingested —
        // which clears the tombstone the delete just wrote. The file would be gone
        // and its content still retrievable, for a source with no cron indefinitely.
        Optional<String> claim = pipeline.claimForMaintenance(ragConfigId, source);
        if (claim.isEmpty()) {
            return DeleteOutcome.BUSY;
        }
        try {
            // The vectors go first. Deleting the file first and then failing to
            // remove its chunks would leave content in the knowledge base that the
            // operator can no longer see, let alone delete.
            boolean vectorsRemoved = pipeline.forgetDocument(ragConfigId, knowledgeBase, source, fileId);
            fileStore.delete(sourceKey, fileId);
            meterRegistry.counter("eddi.ingestion.files.deleted",
                    Tags.of("source", String.valueOf(source.getName()))).increment();
            return vectorsRemoved ? DeleteOutcome.DELETED : DeleteOutcome.DELETED_BUT_CHUNKS_REMAIN;
        } finally {
            // Always, including after a failure: a claim that is never released
            // blocks the source until it is reaped, which is a quarter of an hour of
            // 409s for every run and every delete.
            pipeline.releaseClaim(ragConfigId, source, claim.get(), 1);
        }
    }

    /**
     * Everything one source holds, with what the knowledge base currently knows
     * about each file.
     *
     * <p>
     * Worth the extra read: without it, a file that was uploaded, a file that was
     * indexed, a file whose text could not be extracted and a file that has changed
     * since the last run all look exactly the same in the Manager — and the only
     * way to find out is to run the source and compare counters.
     */
    public List<FileStatus> listWithStatus(String ragConfigId, IngestionSource source) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        Map<String, DocumentState> known = new HashMap<>();
        try {
            for (DocumentState state : stateStore.listDocuments(sourceKey, MAX_DOCUMENT_STATES)) {
                known.put(state.documentId(), state);
            }
        } catch (RuntimeException e) {
            // The files are the answer; their status is a detail. A state store that
            // is unwell must not empty the operator's file list.
            LOGGER.warnf(e, "Could not read the ingestion state of source '%s'; its files are listed without it",
                    LogSanitizer.sanitize(source.getName()));
        }

        List<FileStatus> statuses = new ArrayList<>();
        for (IIngestedFileStore.StoredFile file : fileStore.list(sourceKey)) {
            statuses.add(new FileStatus(file, indexState(known.get(file.fileId()), file)));
        }
        return statuses;
    }

    private static IndexState indexState(DocumentState state, IIngestedFileStore.StoredFile file) {
        if (state == null || state.lastIngestedAt() == null) {
            return IndexState.NOT_INDEXED;
        }
        if (state.tombstoned()) {
            // Removed from retrieval while the file is still here: it was deleted
            // and re-uploaded, or a past run could not read it.
            return IndexState.NOT_INDEXED;
        }
        return state.hasChanged(file.contentHash()) ? IndexState.CHANGED : IndexState.INDEXED;
    }

    /** Removes every file of a source — used when the source itself goes. */
    public long deleteAll(String ragConfigId, IngestionSource source) {
        return fileStore.deleteAll(IngestionPipeline.stateKey(ragConfigId, source));
    }

    /**
     * A file as it arrived, not yet read.
     *
     * <p>
     * A supplier rather than the bytes: a request can carry several files, and
     * materialising all of them before the first is examined makes an upload's peak
     * memory the size of the request — for every request in flight. The runtime has
     * already spooled each part to disk, so reading them one at a time costs
     * nothing but the read.
     */
    public record IncomingFile(String fileName, FileContent content) {

        /** For a caller that already holds the bytes, such as a test. */
        public static IncomingFile of(String fileName, byte[] content) {
            return new IncomingFile(fileName, () -> content);
        }

        @FunctionalInterface
        public interface FileContent {
            byte[] read() throws IOException;
        }
    }

    public record RejectedFile(String fileName, String reason) {
    }

    public record UploadOutcome(List<IIngestedFileStore.StoredFile> accepted, List<RejectedFile> rejected) {
    }

    public enum DeleteOutcome {
        DELETED, DELETED_BUT_CHUNKS_REMAIN, NOT_FOUND,
        /** A run holds the source's claim; deleting under it would race with it. */
        BUSY
    }

    /** Whether the knowledge base currently answers from a file. */
    public enum IndexState {
        /** Uploaded, and no completed run has embedded it yet. */
        NOT_INDEXED,
        /** Its text is in the knowledge base, and the file has not changed since. */
        INDEXED,
        /** It was re-uploaded after it was indexed; the next run picks it up. */
        CHANGED
    }

    public record FileStatus(IIngestedFileStore.StoredFile file, IndexState indexState) {
    }
}
