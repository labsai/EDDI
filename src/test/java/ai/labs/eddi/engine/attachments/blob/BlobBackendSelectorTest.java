/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlobBackendSelectorTest {

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource({
            "auto, mongodb, gridfs",
            "auto, postgres, pg-chunks",
            "'', postgres, pg-chunks",
            "AUTO, mongodb, gridfs",
            "gridfs, mongodb, gridfs",
            " Gridfs , mongodb, gridfs",
            "pg-chunks, postgres, pg-chunks",
            // DataStoreProducers reads anything but "postgres" as MongoDB.
            "auto, something-else, gridfs"})
    void resolvesWhatCanRun(String configured, String datastore, String expected) {
        assertEquals(expected, BlobBackendSelector.resolve(configured, datastore));
    }

    @Test
    void aNullSettingIsAuto() {
        assertEquals("pg-chunks", BlobBackendSelector.resolve(null, "postgres"));
    }

    @Test
    void gridfsOnPostgresFailsWithTheReason() {
        var refused = assertThrows(IllegalStateException.class, () -> BlobBackendSelector.resolve("gridfs", "postgres"));
        assertTrue(refused.getMessage().contains("needs MongoDB"), refused.getMessage());
    }

    @Test
    void pgChunksOnMongoFailsWithTheReason() {
        var refused = assertThrows(IllegalStateException.class, () -> BlobBackendSelector.resolve("pg-chunks", "mongodb"));
        assertTrue(refused.getMessage().contains("needs PostgreSQL"), refused.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"s3", "pg-largeobject"})
    void plannedBackendsAreNotAvailableInThisVersion(String backend) {
        var refused = assertThrows(IllegalStateException.class, () -> BlobBackendSelector.resolve(backend, "postgres"));
        assertTrue(refused.getMessage().contains("not available in this version"), refused.getMessage());
    }

    @Test
    void anUnknownBackendFails() {
        var refused = assertThrows(IllegalStateException.class, () -> BlobBackendSelector.resolve("ftp", "mongodb"));
        assertTrue(refused.getMessage().contains("Unknown"), refused.getMessage());
    }

    @Test
    void theWriteBackendMustBeAvailable() {
        InMemoryBlobBackend memory = new InMemoryBlobBackend();
        assertThrows(IllegalStateException.class, () -> new BlobBackendSelector("pg-chunks", Map.of(memory.name(), () -> memory)));
    }

    @Test
    void readsGoByTheLocatorsBackendAndCreateItOnFirstUse() throws IOException {
        InMemoryBlobBackend writes = new InMemoryBlobBackend();
        BlobBackend other = mock(BlobBackend.class);
        AtomicInteger created = new AtomicInteger();
        BlobBackendSelector selector = new BlobBackendSelector("gridfs", Map.of("gridfs", () -> writes, "other", () -> {
            created.incrementAndGet();
            return other;
        }));

        assertSame(writes, selector.forWrites());
        assertEquals("gridfs", selector.writeBackendName());
        assertEquals(0, created.get(), "a backend nobody uses is never created");
        assertSame(other, selector.forLocator(new BlobLocator("other", "k")));
        assertEquals(1, created.get());
    }

    @Test
    void aLocatorOfABackendThisDeploymentCannotReachIsAnIoFailure() {
        BlobBackendSelector selector = BlobBackendSelector.of(new InMemoryBlobBackend());
        var refused = assertThrows(IOException.class, () -> selector.forLocator(new BlobLocator("s3", "k")));
        assertTrue(refused.getMessage().contains("not available"), refused.getMessage());
    }

    @Test
    void theStartupCheckFailsWithTheProducersReason() {
        @SuppressWarnings("unchecked")
        Instance<BlobBackendSelector> instance = mock(Instance.class);
        when(instance.get()).thenThrow(new RuntimeException("creation failed",
                new IllegalStateException("eddi.attachments.blob.backend=gridfs needs MongoDB")));

        var refused = assertThrows(IllegalStateException.class, () -> new BlobBackendStartupCheck(instance).check());
        assertTrue(refused.getMessage().contains("needs MongoDB"), refused.getMessage());
    }

    @Test
    void theStartupCheckPassesAWorkingSelector() {
        @SuppressWarnings("unchecked")
        Instance<BlobBackendSelector> instance = mock(Instance.class);
        when(instance.get()).thenReturn(BlobBackendSelector.of(new InMemoryBlobBackend()));

        new BlobBackendStartupCheck(instance).check();
    }
}
