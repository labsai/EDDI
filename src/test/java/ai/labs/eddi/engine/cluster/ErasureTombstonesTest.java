/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ErasureTombstones")
class ErasureTombstonesTest {

    @Test
    @DisplayName("a marked conversation reads as marked, an unmarked one does not")
    void marksAreRead() {
        var tombstones = new ErasureTombstones(new InMemorySharedKv("ERASED", Duration.ofDays(7)));

        tombstones.mark("0123456789abcdef01234567");

        assertTrue(tombstones.isMarked("0123456789abcdef01234567"));
        assertFalse(tombstones.isMarked("aaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    @DisplayName("ids that are not plain tokens are hashed, so they cannot address another conversation's key")
    void unsafeIdsAreHashed() {
        var tombstones = new ErasureTombstones(new InMemorySharedKv("ERASED", Duration.ofDays(7)));

        tombstones.mark("a.b/c");

        assertTrue(tombstones.isMarked("a.b/c"));
        assertFalse(tombstones.isMarked("a_b_c"));
    }

    @Test
    @DisplayName("a marker expires after its time to live")
    void markersExpire() {
        var now = new AtomicLong(1_000);
        var tombstones = new ErasureTombstones(new InMemorySharedKv("ERASED", Duration.ofSeconds(10), now::get));

        tombstones.mark("conv-1");
        assertTrue(tombstones.isMarked("conv-1"));
        now.addAndGet(Duration.ofSeconds(11).toMillis());

        assertFalse(tombstones.isMarked("conv-1"));
    }

    @Test
    @DisplayName("a null conversation id is never marked")
    void nullIsNeverMarked() {
        var tombstones = new ErasureTombstones(new InMemorySharedKv("ERASED", Duration.ofDays(7)));

        tombstones.mark(null);

        assertFalse(tombstones.isMarked(null));
    }
}
