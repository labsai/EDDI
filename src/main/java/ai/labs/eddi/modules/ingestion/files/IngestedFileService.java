/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    private final IIngestedFileStore fileStore;
    private final DocumentExtractors extractors;
    private final IngestionPipeline pipeline;

    @Inject
    public IngestedFileService(IIngestedFileStore fileStore, DocumentExtractors extractors,
            IngestionPipeline pipeline) {
        this.fileStore = fileStore;
        this.extractors = extractors;
        this.pipeline = pipeline;
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

        IIngestedFileStore.Usage usage = fileStore.usage(sourceKey);
        long usedBytes = usage.totalBytes();
        int usedFiles = usage.fileCount();
        // What each name already occupies, so replacing a 10 MB file with a 1 MB one
        // frees space rather than counting both against the source's total. Kept up
        // to date as the batch proceeds: twenty files have to be measured against
        // each other, not only against what was there when the request arrived.
        Map<String, Long> sizeByFileId = new HashMap<>();
        for (IIngestedFileStore.StoredFile stored : fileStore.list(sourceKey)) {
            sizeByFileId.put(stored.fileId(), stored.sizeBytes());
        }

        List<IIngestedFileStore.StoredFile> accepted = new ArrayList<>();
        List<RejectedFile> rejected = new ArrayList<>();

        for (IncomingFile file : incoming) {
            String fileName = IngestedFileIds.sanitize(file.fileName());
            String fileId = IngestedFileIds.forFileName(fileName);
            byte[] content = file.content();

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
        if (fileName.isBlank()) {
            return "This file has no name.";
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
        // The vectors go first. Deleting the file first and then failing to remove
        // its chunks would leave content in the knowledge base that the operator can
        // no longer see, let alone delete.
        boolean vectorsRemoved = pipeline.forgetDocument(ragConfigId, knowledgeBase, source, fileId);
        fileStore.delete(sourceKey, fileId);
        return vectorsRemoved ? DeleteOutcome.DELETED : DeleteOutcome.DELETED_BUT_CHUNKS_REMAIN;
    }

    /** Removes every file of a source — used when the source itself goes. */
    public long deleteAll(String ragConfigId, IngestionSource source) {
        return fileStore.deleteAll(IngestionPipeline.stateKey(ragConfigId, source));
    }

    /** A file as it arrived, already read into memory. */
    public record IncomingFile(String fileName, byte[] content) {
    }

    public record RejectedFile(String fileName, String reason) {
    }

    public record UploadOutcome(List<IIngestedFileStore.StoredFile> accepted, List<RejectedFile> rejected) {
    }

    public enum DeleteOutcome {
        DELETED, DELETED_BUT_CHUNKS_REMAIN, NOT_FOUND
    }
}
