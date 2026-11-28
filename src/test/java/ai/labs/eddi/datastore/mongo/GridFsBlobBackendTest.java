/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The read batch of {@link GridFsBlobBackend}: what needs no MongoDB. The rest
 * is in {@code GridFsBlobBackendIT}.
 */
@DisplayName("GridFsBlobBackend read batch")
class GridFsBlobBackendTest {

    private static final int CHUNK = 255 * 1024;
    private static final long OBJECT = 100L * CHUNK;

    @Test
    @DisplayName("a whole-object read batches about 4 MiB of chunks")
    void wholeObject() {
        assertEquals(16, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, 0, -1));
        assertEquals(64, GridFsBlobBackend.readBatchSize(64 * 1024, OBJECT, 0, -1));
    }

    @Test
    @DisplayName("a range batches no more chunks than it covers")
    void rangeCoversOnlyItsChunks() {
        assertEquals(1, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, 0, 1), "one byte, one chunk");
        assertEquals(1, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, 5L * CHUNK + 3, CHUNK - 3), "inside one chunk");
        assertEquals(2, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, CHUNK - 1, 2), "straddles a boundary");
        assertEquals(3, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, CHUNK, 3L * CHUNK), "three whole chunks");
    }

    @Test
    @DisplayName("a long range is still capped at the whole-object batch, and clipped at the object's end")
    void longRange() {
        assertEquals(16, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, 0, Long.MAX_VALUE));
        assertEquals(2, GridFsBlobBackend.readBatchSize(CHUNK, OBJECT, OBJECT - CHUNK - 1, Long.MAX_VALUE));
    }
}
