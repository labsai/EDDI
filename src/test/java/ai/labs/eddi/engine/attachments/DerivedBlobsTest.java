/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.Derivation.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("DerivedBlobs, Derivation, OwnerScope")
class DerivedBlobsTest {

    private static final AttachmentRootDeleted A = new AttachmentRootDeleted("8d4f6f1e-0000-4000-8000-00000000000a", OwnerScope.CONVERSATION,
            "6aba70011a25f8be7920e001", null);
    private static final AttachmentRootDeleted B = new AttachmentRootDeleted("8d4f6f1e-0000-4000-8000-00000000000b", OwnerScope.CONVERSATION,
            "6aba70011a25f8be7920e001", "user-1");

    @Test
    void sha256IsLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DerivedBlobs.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void storedViewsAreCountedByKindOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DerivedBlobs.recordStored(registry, Kind.IMAGE, 100);
        DerivedBlobs.recordStored(registry, Kind.IMAGE, 50);
        DerivedBlobs.recordStored(registry, Kind.TEXT_PART, 7);

        assertEquals(2.0, registry.get(DerivedBlobs.METRIC_STORED).tag("kind", "IMAGE").counter().count());
        assertEquals(150.0, registry.get(DerivedBlobs.METRIC_BYTES).tag("kind", "IMAGE").counter().count());
        assertEquals(7.0, registry.get(DerivedBlobs.METRIC_BYTES).tag("kind", "TEXT_PART").counter().count());
        for (Meter meter : registry.getMeters()) {
            assertEquals(List.of("kind"), meter.getId().getTags().stream().map(t -> t.getKey()).toList(), "no ref, no name in a tag");
        }
        assertDoesNotThrow(() -> DerivedBlobs.recordStored(null, Kind.IMAGE, 1), "outside CDI there is no registry");
        Counter none = registry.find(DerivedBlobs.METRIC_STORED).tag("kind", "AUDIO").counter();
        assertNull(none);
    }

    @Test
    void everyRootIsAnnouncedEvenWhenAnObserverFails() {
        List<AttachmentRootDeleted> seen = new ArrayList<>();
        RecordingEvent<AttachmentRootDeleted> recording = new RecordingEvent<>();
        // An observer that throws on the first root must not stop the second.
        var failingFirst = new RecordingEvent<AttachmentRootDeleted>() {
            @Override
            public void fire(AttachmentRootDeleted event) {
                seen.add(event);
                if (event == A) {
                    throw new IllegalStateException("observer failed");
                }
            }
        };

        assertDoesNotThrow(() -> DerivedBlobs.fireRootDeleted(failingFirst, List.of(A, B)));
        assertEquals(List.of(A, B), seen);

        DerivedBlobs.fireRootDeleted(recording, List.of(B));
        assertEquals(List.of(B), recording.fired());
        assertDoesNotThrow(() -> DerivedBlobs.fireRootDeleted(null, List.of(A)));
    }

    @Test
    void aDerivationNeedsAKindAndRecordsWhatTheStoreResolved() {
        assertThrows(IllegalArgumentException.class, () -> new Derivation("r", null, null, null, null, null));

        Derivation asked = Derivation.of("view", Kind.MANIFEST_PART, "m.1").withPage(4).withParent("parent");
        Derivation stored = asked.resolved("root", "parent", "abc");

        assertEquals(new Derivation("root", "parent", Kind.MANIFEST_PART, "m.1", 4, "abc"), stored);
        assertEquals(Kind.PDF_SLICE, Kind.fromStored(" pdf_slice "));
        assertNull(Kind.fromStored(null));
    }

    @Test
    void anAbsentScopeIsTheConversation() {
        assertEquals(OwnerScope.CONVERSATION, OwnerScope.fromStored(null));
        assertEquals(OwnerScope.CONVERSATION, OwnerScope.fromStored(" "));
        assertEquals(OwnerScope.TEAM, OwnerScope.fromStored("team"));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.fromStored("GALAXY"));
    }
}
