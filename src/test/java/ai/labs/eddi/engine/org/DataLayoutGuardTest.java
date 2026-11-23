/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataLayoutGuardTest {

    /** A store whose reads fail a given number of times, then return the record. */
    static class FakeStore implements IOrgMarkerStore {
        final OrgMarker record;
        int failuresLeft;
        int reads;

        FakeStore(OrgMarker record, int failures) {
            this.record = record;
            this.failuresLeft = failures;
        }

        @Override
        public OrgMarker read() {
            reads++;
            if (failuresLeft-- > 0) {
                throw new IllegalStateException("database unreachable");
            }
            return record;
        }

        @Override
        public boolean moveMarker(OrgMarkerState expected, OrgMarkerState next) {
            throw new UnsupportedOperationException("the guard never writes");
        }

        @Override
        public int raiseRequiredLayout(int level) {
            throw new UnsupportedOperationException("the guard never writes");
        }
    }

    /** A virtual clock that advances only when the guard sleeps. */
    private final AtomicLong nanos = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();

    private DataLayoutGuard guard(IOrgMarkerStore store, Duration wait) {
        return new DataLayoutGuard(store, wait, nanos::get, millis -> {
            sleeps.add(millis);
            nanos.addAndGet(millis * 1_000_000);
        });
    }

    @Test
    void aDeploymentWithNoRecordStarts() {
        FakeStore store = new FakeStore(OrgMarker.NONE, 0);

        assertSame(OrgMarker.NONE, guard(store, Duration.ofSeconds(60)).check());
        assertEquals(1, store.reads);
    }

    @Test
    void theSupportedLayoutStarts() {
        OrgMarker record = new OrgMarker(OrgMarkerState.ABSENT, DataLayoutGuard.SUPPORTED_LAYOUT, null);

        assertSame(record, guard(new FakeStore(record, 0), Duration.ofSeconds(60)).check());
    }

    @Test
    void aHigherLayoutIsRefusedEvenWhenTheMarkerIsAbsent() {
        OrgMarker raised = new OrgMarker(OrgMarkerState.ABSENT, DataLayoutGuard.SUPPORTED_LAYOUT + 1, null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard(new FakeStore(raised, 0), Duration.ZERO).check());
        assertTrue(e.getMessage().startsWith("[SECURITY] The data layout requires level 1"), e.getMessage());
    }

    @Test
    void anEnabledDeploymentIsRefusedByThisRelease() {
        OrgMarker enabled = new OrgMarker(OrgMarkerState.ENABLED, 1, null);

        assertThrows(IllegalStateException.class, () -> guard(new FakeStore(enabled, 0), Duration.ofSeconds(60)).check());
    }

    @Test
    void aTransientFailureIsRetriedWithGrowingBackoff() {
        FakeStore store = new FakeStore(OrgMarker.NONE, 3);

        assertSame(OrgMarker.NONE, guard(store, Duration.ofSeconds(60)).check());

        assertEquals(4, store.reads);
        assertEquals(List.of(500L, 1_000L, 2_000L), sleeps);
    }

    @Test
    void anUnreadableRecordRefusesToStartAfterTheWait() {
        FakeStore store = new FakeStore(OrgMarker.NONE, Integer.MAX_VALUE);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard(store, Duration.ofSeconds(10)).check());

        assertTrue(e.getMessage().startsWith("[SECURITY] Could not read the data layout record"), e.getMessage());
        assertEquals("database unreachable", e.getCause().getMessage());
        // never sleeps past the deadline: the sleeps add up to exactly the wait
        assertEquals(10_000L, sleeps.stream().mapToLong(Long::longValue).sum());
        assertTrue(sleeps.stream().allMatch(s -> s <= DataLayoutGuard.MAX_BACKOFF_MS), sleeps.toString());
    }

    @Test
    void aZeroWaitTriesExactlyOnce() {
        FakeStore store = new FakeStore(OrgMarker.NONE, 1);

        assertThrows(IllegalStateException.class, () -> guard(store, Duration.ZERO).check());
        assertEquals(1, store.reads);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void anInterruptStopsTheWaitAndKeepsTheFlag() {
        FakeStore store = new FakeStore(OrgMarker.NONE, Integer.MAX_VALUE);
        DataLayoutGuard interrupted = new DataLayoutGuard(store, Duration.ofSeconds(60), nanos::get, millis -> {
            throw new InterruptedException("shutdown");
        });

        try {
            assertThrows(IllegalStateException.class, interrupted::check);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anUninterpretableRecordIsRefusedAtOnceWithoutRetrying() {
        IOrgMarkerStore corrupt = new FakeStore(OrgMarker.NONE, 0) {
            @Override
            public OrgMarker read() {
                reads++;
                throw new UnsupportedLayoutRecordException("unknown org marker state 'FUTURE'");
            }
        };

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard(corrupt, Duration.ofSeconds(60)).check());

        assertTrue(e.getMessage().startsWith("[SECURITY] The data layout record cannot be interpreted"), e.getMessage());
        assertEquals(1, ((FakeStore) corrupt).reads);
        assertTrue(sleeps.isEmpty(), "a corrupt record is not an outage: no retry");
    }

    @Test
    void anEnormousWaitDoesNotOverflowTheDeadline() {
        FakeStore store = new FakeStore(OrgMarker.NONE, 2);

        assertSame(OrgMarker.NONE, guard(store, Duration.ofDays(999_999)).check());
        assertEquals(3, store.reads);
    }

    @Test
    void theGuardRunsFirstAndAloneAtIntegerMinValue() throws Exception {
        Method onStart = DataLayoutGuard.class.getDeclaredMethod("onStart", StartupEvent.class);
        Priority priority = onStart.getParameters()[0].getAnnotation(Priority.class);
        assertEquals(Integer.MIN_VALUE, priority.value());

        // DV-11: the priority is reserved. Nothing else may tie with the guard.
        List<Path> others;
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            others = files.filter(f -> f.toString().endsWith(".java")).filter(f -> !f.endsWith("DataLayoutGuard.java")).filter(f -> {
                try {
                    return Files.readString(f).contains("@Priority(Integer.MIN_VALUE)");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        }
        assertTrue(others.isEmpty(), "@Priority(Integer.MIN_VALUE) is reserved for DataLayoutGuard: " + others);
    }

    @Test
    void storedValuesAreInterpretedStrictly() {
        assertEquals(new OrgMarker(OrgMarkerState.ABSENT, 0, null), OrgMarker.parse(null, null, null));
        assertEquals(2, OrgMarker.parse("SWITCHING", 2L, null).requiredLayout());
        assertEquals(3, OrgMarker.parse("ENABLED", 3.0d, null).requiredLayout());
        assertEquals(OrgMarkerState.ENABLED, OrgMarker.parse("ENABLED", 1, null).state());

        // anything that would read as a lower level, or as a known state, is refused
        // instead
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("ABSENT", 0.9d, null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("ABSENT", 4_294_967_296L, null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("ABSENT", -1, null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("ABSENT", Double.POSITIVE_INFINITY, null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("ABSENT", "1", null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse("FUTURE", 0, null));
        assertThrows(UnsupportedLayoutRecordException.class, () -> OrgMarker.parse(1, 0, null));
    }

    @Test
    void theMarkerOnlyMovesForward() {
        assertTrue(OrgMarkerState.ABSENT.canMoveTo(OrgMarkerState.SWITCHING));
        assertTrue(OrgMarkerState.ABSENT.canMoveTo(OrgMarkerState.ENABLED));
        assertTrue(OrgMarkerState.SWITCHING.canMoveTo(OrgMarkerState.ENABLED));
        for (OrgMarkerState from : OrgMarkerState.values()) {
            assertEquals(false, from.canMoveTo(from), from.name());
            assertEquals(false, OrgMarkerState.ENABLED.canMoveTo(from), from.name());
        }
        assertEquals(false, OrgMarkerState.SWITCHING.canMoveTo(OrgMarkerState.ABSENT));
        assertThrows(IllegalArgumentException.class, () -> new OrgMarker(OrgMarkerState.ABSENT, -1, null));
    }
}
