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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** An in-memory store with a controllable failure. */
    private static final class Store implements IWorkspaceSettingsStore {
        final AtomicReference<StoredWorkspaceSettings> value = new AtomicReference<>();
        volatile boolean failing;

        @Override
        public Optional<StoredWorkspaceSettings> read(String tenantId) {
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

            assertTrue(settings(null, null).admitsLegacy());
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
}
