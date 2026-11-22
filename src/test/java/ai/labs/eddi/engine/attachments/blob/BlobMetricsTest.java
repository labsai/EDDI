/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlobMetricsTest {

    @Test
    void readsAreCountedOnceWhenTheStreamCloses() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InputStream in = BlobMetrics.countingReads(registry, "pg-chunks", new ByteArrayInputStream(new byte[1_000]));

        in.readNBytes(600);
        in.read();
        in.close();
        in.close();

        assertEquals(601, registry.counter(BlobMetrics.BYTES, "backend", "pg-chunks", "direction", "out").count());
    }

    @Test
    void writesAndTombstonesAreCountedByBackendOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlobMetrics.recordWritten(registry, "gridfs", 4_096);
        BlobMetrics.recordTombstone(registry, "gridfs");

        assertEquals(4_096, registry.counter(BlobMetrics.BYTES, "backend", "gridfs", "direction", "in").count());
        assertEquals(1, registry.counter(BlobMetrics.TOMBSTONES, "backend", "gridfs").count());
    }

    @Test
    void withoutARegistryNothingIsWrapped() {
        InputStream in = new ByteArrayInputStream(new byte[1]);
        assertSame(in, BlobMetrics.countingReads(null, "gridfs", in));
        BlobMetrics.recordWritten(null, "gridfs", 1);
        BlobMetrics.recordTombstone(null, "gridfs");
    }
}
