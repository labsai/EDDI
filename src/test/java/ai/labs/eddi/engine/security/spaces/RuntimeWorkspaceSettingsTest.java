/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.engine.security.spaces.rest.RestWorkspaces;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceSettingsView;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore.StoredWorkspaceSettings;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The default space and the legacy policy became runtime settings. What must
 * hold: a pinned property still wins and cannot be changed through the API, a
 * stored value reaches other replicas within the cache window, and a bad stored
 * value can never widen access by accident.
 */
@DisplayName("runtime workspace settings")
class RuntimeWorkspaceSettingsTest {

    /**
     * An in-memory store with a controllable failure, a read counter, and a read
     * that can be held open: a database call that has not answered yet.
     */
    private static final class Store implements IWorkspaceSettingsStore {
        final AtomicReference<StoredWorkspaceSettings> value = new AtomicReference<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean failing;
        volatile CountDownLatch entered;
        private volatile CountDownLatch gate;

        /**
         * The next read counts down {@link #entered}, then waits for {@link #release}.
         */
        void holdNextRead() {
            entered = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch held = gate;
            gate = null;
            if (held != null) {
                held.countDown();
            }
        }

        @Override
        public Optional<StoredWorkspaceSettings> read(String tenantId) {
            reads.incrementAndGet();
            CountDownLatch held = gate;
            if (held != null) {
                entered.countDown();
                try {
                    held.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failing) {
                throw new IllegalStateException("store down");
            }
            return Optional.ofNullable(value.get());
        }

        @Override
        public void write(String tenantId, StoredWorkspaceSettings settings) {
            value.set(settings);
        }
    }

    private Store store;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        store = new Store();
        clock = new AtomicLong(0);
    }

    private WorkspaceSettings settings(String pinnedLegacy, String pinnedSpace) {
        return new WorkspaceSettings(true, true, "groups", pinnedLegacy, Optional.ofNullable(pinnedSpace), store, clock::get);
    }

    private static StoredWorkspaceSettings stored(String space, String legacy) {
        return new StoredWorkspaceSettings(space, legacy, Instant.EPOCH, "admin");
    }

    private void expireCache() {
        clock.addAndGet(WorkspaceSettings.STORED_SETTINGS_TTL.toNanos() + 1);
    }

    /** A caller whose access check triggers the re-read, on its own thread. */
    private static Thread refreshInBackground(WorkspaceSettings s) {
        Thread refresher = new Thread(s::admitsLegacy, "settings-refresher");
        refresher.setDaemon(true);
        refresher.start();
        return refresher;
    }

    @Nested
    @DisplayName("effective values")
    class Effective {

        @Test
        @DisplayName("nothing set means personal spaces and the permissive legacy policy an upgrade always had")
        void defaults() {
            var s = settings(null, null);

            assertTrue(s.getDefaultSpaceTeam().isEmpty());
            assertTrue(s.admitsLegacy());
        }

        @Test
        @DisplayName("a stored value applies")
        void storedApplies() {
            store.write("default", stored("engineering", "admin-only"));
            var s = settings(null, null);

            assertEquals(Optional.of("engineering"), s.getDefaultSpaceTeam());
            assertFalse(s.admitsLegacy());
        }

        @Test
        @DisplayName("a pinned property beats a stored value")
        void pinnedWins() {
            store.write("default", stored("engineering", "shared"));
            var s = settings("admin-only", "finance");

            assertEquals(Optional.of("finance"), s.getDefaultSpaceTeam());
            assertFalse(s.admitsLegacy());
        }

        @Test
        @DisplayName("a corrupt stored legacy value falls back to the default rather than being guessed at")
        void corruptStoredLegacyIgnored() {
            store.write("default", stored(null, "everyone"));
            var s = settings(null, null);

            assertTrue(s.admitsLegacy());
            assertEquals(WorkspaceSettings.LEGACY_SHARED, s.effectiveLegacyVisibility());
            assertEquals(null, s.storedSettings().orElseThrow().legacyVisibility(),
                    "ignored at load, so the settings page reports the default that actually applies");
        }

        @Test
        @DisplayName("a corrupt stored legacy value is reported once per read of the store, not on every access check")
        void corruptStoredLegacyWarnsOnce() {
            // Every guarded request asks admitsLegacy(), so validating, and warning, on
            // each call turned one bad row into a WARN per access check.
            store.write("default", stored(null, "everyone"));
            var s = settings(null, null);

            List<LogRecord> warnings = captureWarnings(() -> {
                for (int i = 0; i < 100; i++) {
                    assertTrue(s.admitsLegacy());
                }
            });

            assertEquals(1, warnings.size(), "one WARN for the one read of the store");
        }

        @Test
        @DisplayName("the same corrupt value read again is not reported again, a different one is")
        void corruptStoredLegacyWarnsOnChangeOnly() {
            store.write("default", stored(null, "everyone"));
            var s = settings(null, null);

            List<LogRecord> warnings = captureWarnings(() -> {
                s.admitsLegacy();
                expireCache();
                s.admitsLegacy();
                store.write("default", stored(null, "nobody"));
                expireCache();
                s.admitsLegacy();
            });

            assertEquals(2, warnings.size(), "a WARN every five seconds for one row is still noise; a new bad value is news");
        }

        @Test
        @DisplayName("a stored value is matched whatever its case and padding")
        void storedLegacyNormalised() {
            store.write("default", stored(null, " ADMIN-ONLY "));
            var s = settings(null, null);

            assertFalse(s.admitsLegacy());
            assertEquals(WorkspaceSettings.LEGACY_ADMIN_ONLY, s.effectiveLegacyVisibility());
        }

        @Test
        @DisplayName("another replica's write is seen once the cache window passes, not before")
        void cacheWindow() {
            var s = settings(null, null);
            assertTrue(s.getDefaultSpaceTeam().isEmpty());

            store.write("default", stored("engineering", null));
            assertTrue(s.getDefaultSpaceTeam().isEmpty(), "within the window the cached value is served");

            clock.addAndGet(WorkspaceSettings.STORED_SETTINGS_TTL.toNanos() + 1);
            assertEquals(Optional.of("engineering"), s.getDefaultSpaceTeam());
        }

        @Test
        @DisplayName("a store outage keeps the values last read")
        void outageKeepsLastValues() {
            store.write("default", stored("engineering", "admin-only"));
            var s = settings(null, null);
            assertFalse(s.admitsLegacy());

            store.failing = true;
            clock.addAndGet(WorkspaceSettings.STORED_SETTINGS_TTL.toNanos() + 1);

            assertFalse(s.admitsLegacy(), "an outage must not silently re-open legacy data to everyone");
            assertEquals(Optional.of("engineering"), s.getDefaultSpaceTeam());
        }

        @Test
        @DisplayName("a store that cannot be read at all keeps unowned resources closed, until it can")
        void unreadableFromTheStartFailsClosed() {
            // No value was ever read, so what the administrator stored is unknown.
            // Answering the permissive default opened every unowned resource to every
            // user whenever the store blinked at startup.
            store.write("default", stored(null, "shared"));
            store.failing = true;
            var s = settings(null, null);

            assertFalse(s.admitsLegacy());
            assertEquals(Optional.empty(), s.getDefaultSpaceTeam(), "no default team: people keep their own space");

            store.failing = false;
            clock.addAndGet(WorkspaceSettings.STORED_SETTINGS_TTL.toNanos() + 1);
            assertTrue(s.admitsLegacy(), "once readable, the stored value applies");
        }

        @Test
        @DisplayName("an expired value is re-read by one caller while everyone else is served the last one")
        void staleServedWhileOneCallerRereads() throws Exception {
            // The store read has no timeout of its own. Re-reading under a lock queued
            // every access check behind it, at every expiry and for the whole of an
            // outage.
            store.write("default", stored("engineering", "admin-only"));
            var s = settings(null, null);
            assertFalse(s.admitsLegacy());

            store.write("default", stored("finance", "shared"));
            store.holdNextRead();
            expireCache();
            Thread refresher = refreshInBackground(s);
            try {
                assertTrue(store.entered.await(5, TimeUnit.SECONDS), "the refresher reached the store");

                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    assertEquals(WorkspaceSettings.LEGACY_ADMIN_ONLY, s.effectiveLegacyVisibility());
                    assertEquals(Optional.of("engineering"), s.getDefaultSpaceTeam());
                }, "a reader must not wait for another caller's store read");
                assertEquals(2, store.reads.get(), "only the one caller went to the store");
            } finally {
                store.release();
                refresher.join(5_000);
            }

            assertTrue(s.admitsLegacy(), "the value read is served once it arrives");
            assertEquals(Optional.of("finance"), s.getDefaultSpaceTeam());
        }

        @Test
        @DisplayName("a write adopted while a re-read is in flight is not overwritten by that read")
        void adoptDuringRereadWins() throws Exception {
            store.write("default", stored("engineering", "admin-only"));
            var s = settings(null, null);
            s.admitsLegacy();

            store.holdNextRead();
            expireCache();
            Thread refresher = refreshInBackground(s);
            try {
                assertTrue(store.entered.await(5, TimeUnit.SECONDS), "the refresher reached the store");
                // What this instance just wrote; the in-flight read began before it.
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> s.adopt(stored("ops", "shared")));
            } finally {
                store.release();
                refresher.join(5_000);
            }

            assertEquals(Optional.of("ops"), s.getDefaultSpaceTeam(), "the read that started first must not win");
            assertTrue(s.admitsLegacy());
        }

        @Test
        @DisplayName("team names are normalised whichever way they are written")
        void normalisesTeam() {
            assertEquals("engineering", WorkspaceSettings.normalizeTeam(" /engineering/ "));
            assertEquals("engineering", WorkspaceSettings.normalizeTeam("team:engineering"));
            assertEquals(null, WorkspaceSettings.normalizeTeam("  "));
        }
    }

    @Nested
    @DisplayName("PUT /workspaces/settings")
    class Update {

        private RestWorkspaces rest(WorkspaceSettings s) {
            var spaceContext = mock(SpaceContext.class);
            when(spaceContext.currentPrincipal()).thenReturn("admin");
            return new RestWorkspaces(spaceContext, s, mock(ResourceAccessGuard.class), mock(UserDirectory.class), store);
        }

        @Test
        @DisplayName("stores, adopts at once, and reports the source of each value")
        void storesAndReports() {
            var s = settings(null, null);

            WorkspaceSettingsView view = rest(s).updateSettings(new WorkspaceSettingsView.Update("/engineering", "ADMIN-ONLY"));

            assertEquals("engineering", store.value.get().defaultSpace());
            assertEquals("admin-only", store.value.get().legacyVisibility());
            assertEquals("admin", store.value.get().updatedBy());
            Instant updatedAt = store.value.get().updatedAt();
            assertEquals(updatedAt.truncatedTo(ChronoUnit.MILLIS), updatedAt,
                    "written to the millisecond both stores keep, so a replica reads back what this one adopted");
            assertEquals(WorkspaceSettingsView.Source.STORED, view.defaultSpace().source());
            assertEquals("engineering", view.defaultSpace().value());
            assertFalse(s.admitsLegacy(), "the writing instance must not wait out its own cache");
        }

        @Test
        @DisplayName("an unrecognised legacy value is refused before anything is stored")
        void badLegacyRefused() {
            var s = settings(null, null);

            assertThrows(BadRequestException.class, () -> rest(s).updateSettings(new WorkspaceSettingsView.Update(null, "everyone")));
            assertEquals(null, store.value.get());
        }

        @Test
        @DisplayName("changing a pinned value is a 409, and restating it is fine")
        void pinnedConflict() {
            var s = settings("shared", "finance");

            var conflict = assertThrows(ClientErrorException.class,
                    () -> rest(s).updateSettings(new WorkspaceSettingsView.Update("engineering", null)));
            assertEquals(409, conflict.getResponse().getStatus());

            WorkspaceSettingsView view = rest(s).updateSettings(new WorkspaceSettingsView.Update("team:finance", "shared"));
            assertEquals(WorkspaceSettingsView.Source.PINNED, view.defaultSpace().source());
            assertEquals(null, store.value.get().defaultSpace(), "restating a pinned value must not seed the store with a copy of it");
        }

        @Test
        @DisplayName("an unreadable store aborts the update, so a pinned field's stored value cannot be erased")
        void unreadableStoreChangesNothing() {
            // The settings cache keeps its last values when a read fails; an update built
            // from that snapshot wrote null over what was stored for a pinned field.
            store.write("default", stored("engineering", "admin-only"));
            var s = settings("shared", null);
            store.failing = true;

            assertThrows(InternalServerErrorException.class, () -> rest(s).updateSettings(new WorkspaceSettingsView.Update("finance", null)));

            store.failing = false;
            assertEquals("admin-only", store.value.get().legacyVisibility(), "nothing was written");
        }

        @Test
        @DisplayName("null unsets a stored value")
        void nullUnsets() {
            store.write("default", stored("engineering", "admin-only"));
            var s = settings(null, null);

            WorkspaceSettingsView view = rest(s).updateSettings(new WorkspaceSettingsView.Update(null, null));

            assertEquals(WorkspaceSettingsView.Source.DEFAULT, view.defaultSpace().source());
            assertTrue(s.admitsLegacy());
        }

        @Test
        @DisplayName("fixed settings cannot be changed at all")
        void fixedSettingsAreReadOnly() {
            var fixed = new WorkspaceSettings(true, true, "groups", "shared", Optional.empty());
            var spaceContext = mock(SpaceContext.class);
            var sut = new RestWorkspaces(spaceContext, fixed, mock(ResourceAccessGuard.class), mock(UserDirectory.class));

            var conflict = assertThrows(ClientErrorException.class, () -> sut.updateSettings(new WorkspaceSettingsView.Update(null, null)));
            assertEquals(409, conflict.getResponse().getStatus());
        }
    }

    /**
     * Collects the WARN records {@link WorkspaceSettings} logs while {@code action}
     * runs. logging.properties turns the ai.labs.eddi namespace off for plain unit
     * tests, so the logger is opened explicitly.
     */
    private static List<LogRecord> captureWarnings(Runnable action) {
        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(WorkspaceSettings.class.getName());
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return List.copyOf(captured);
    }
}
