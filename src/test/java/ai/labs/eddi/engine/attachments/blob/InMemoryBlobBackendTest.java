/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import org.junit.jupiter.api.DisplayName;

/**
 * The {@link BlobBackendContract} against the in-memory test double, so the
 * contract itself runs in every unit build; the real backends run it in
 * {@code GridFsBlobBackendIT} and {@code PgChunksBlobBackendIT}.
 */
@DisplayName("BlobBackend contract (in memory)")
class InMemoryBlobBackendTest implements BlobBackendContract {

    private final InMemoryBlobBackend backend = new InMemoryBlobBackend();

    @Override
    public BlobBackend backend() {
        return backend;
    }

    @Override
    public BlobBackend backendWithChunkBytes(int chunkBytes) {
        return new InMemoryBlobBackend(backend.objects());
    }

    @Override
    public int chunkBytes() {
        return 1024;
    }

    @Override
    public long storedRecordCount() {
        return backend.objects().size();
    }
}
