/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The files an upload source holds, kept so every run can re-read them.
 *
 * <p>
 * Keyed by source key — {@code <ragConfigId>:<sourceId>}, the same key
 * {@code IIngestionStateStore} uses — so a file, the document state derived
 * from it, and the vectors it produced are all addressed the same way, and a
 * purge or a delete cannot leave two of the three disagreeing.
 *
 * <p>
 * A file's id is derived from its name, so uploading {@code handbook.pdf} twice
 * replaces it rather than accumulating a second copy that retrieval would then
 * return alongside the first. That is also what makes an upload source
 * re-runnable: the run sees one document per name, whatever the upload history.
 */
public interface IIngestedFileStore {

    /**
     * Stores a file, replacing any file of the same name under the same source.
     *
     * @throws IngestedFileStoreException
     *             when the store cannot accept it
     */
    StoredFile store(String sourceKey, String fileName, String mimeType, byte[] content);

    /** Every file of a source, oldest upload first. */
    List<StoredFile> list(String sourceKey);

    /** One file's metadata. */
    Optional<StoredFile> find(String sourceKey, String fileId);

    /**
     * One file's bytes.
     *
     * @return empty when no such file exists — a file deleted between a run's
     *         listing and its read is an ordinary race, not an error
     */
    Optional<byte[]> load(String sourceKey, String fileId);

    /** @return whether a file was removed */
    boolean delete(String sourceKey, String fileId);

    /** Removes every file of a source. */
    long deleteAll(String sourceKey);

    /** What a source currently holds, for enforcing its limits. */
    Usage usage(String sourceKey);

    /**
     * A stored file.
     *
     * @param fileId
     *            derived from the name, stable across re-uploads, and the document
     *            id the ingestion state and the vectors are keyed by
     * @param contentHash
     *            SHA-256 of the bytes, so the Manager can show that a re-upload
     *            really changed something
     */
    record StoredFile(
            String fileId,
            String sourceKey,
            String fileName,
            String mimeType,
            long sizeBytes,
            String contentHash,
            Instant uploadedAt) {
    }

    /** How many files a source holds and how much space they take. */
    record Usage(int fileCount, long totalBytes) {

        public static Usage empty() {
            return new Usage(0, 0L);
        }
    }

    /**
     * A store failure. Never thrown for "no such file" — that is an empty result.
     */
    class IngestedFileStoreException extends RuntimeException {

        public IngestedFileStoreException(String message) {
            super(message);
        }

        public IngestedFileStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
