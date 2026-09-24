/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.modules.ingestion.ContentHashes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link IIngestedFileStore} that keeps everything in a map.
 *
 * <p>
 * Held to the same contract as the MongoDB and PostgreSQL stores by
 * {@code IngestedFileStoreContract}, so a test using this double is testing the
 * behaviour the real stores are required to have — not a convenient
 * approximation of it.
 */
public class InMemoryIngestedFileStore implements IIngestedFileStore {

    private final Map<String, Map<String, Entry>> bySourceKey = new ConcurrentHashMap<>();

    private record Entry(StoredFile metadata, byte[] content) {
    }

    @Override
    public StoredFile store(String sourceKey, String fileName, String mimeType, byte[] content) {
        String fileId = IngestedFileIds.forFileName(fileName);
        StoredFile metadata = new StoredFile(fileId, sourceKey, fileName, mimeType, content.length,
                ContentHashes.sha256Bytes(content), Instant.now());
        // LinkedHashMap so listing order is upload order, as the real stores sort it.
        bySourceKey.computeIfAbsent(sourceKey, key -> new LinkedHashMap<>())
                .put(fileId, new Entry(metadata, content.clone()));
        return metadata;
    }

    @Override
    public List<StoredFile> list(String sourceKey) {
        synchronized (this) {
            return new ArrayList<>(files(sourceKey).values().stream().map(Entry::metadata).toList());
        }
    }

    @Override
    public Optional<StoredFile> find(String sourceKey, String fileId) {
        return Optional.ofNullable(files(sourceKey).get(fileId)).map(Entry::metadata);
    }

    @Override
    public Optional<byte[]> load(String sourceKey, String fileId) {
        return Optional.ofNullable(files(sourceKey).get(fileId)).map(entry -> entry.content().clone());
    }

    @Override
    public boolean delete(String sourceKey, String fileId) {
        return files(sourceKey).remove(fileId) != null;
    }

    @Override
    public long deleteAll(String sourceKey) {
        Map<String, Entry> files = bySourceKey.remove(sourceKey);
        return files == null ? 0 : files.size();
    }

    @Override
    public Usage usage(String sourceKey) {
        Map<String, Entry> files = files(sourceKey);
        long bytes = files.values().stream().mapToLong(entry -> entry.metadata().sizeBytes()).sum();
        return new Usage(files.size(), bytes);
    }

    private Map<String, Entry> files(String sourceKey) {
        return bySourceKey.computeIfAbsent(sourceKey, key -> new LinkedHashMap<>());
    }
}
