/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link VaultSaltManager} — salt lifecycle (load, generate, migrate,
 * legacy fallback).
 */
@DisplayName("VaultSaltManager")
class VaultSaltManagerTest {

    private static final byte[] OTHER_SALT = {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
    private static final byte[] LEGACY_SALT = "eddi-vault-kek-v1".getBytes(StandardCharsets.UTF_8);

    private ISecretPersistence persistence;
    private VaultSaltManager saltManager;

    @BeforeEach
    void setUp() {
        persistence = mock(ISecretPersistence.class);
        saltManager = new VaultSaltManager(persistence);
        // An insert-if-absent into an empty store: the caller's value wins.
        lenient().when(persistence.putMetaValueIfAbsent(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Nested
    @DisplayName("initialize")
    class InitializeTests {

        @Test
        @DisplayName("loads existing salt from persistence")
        void loadsExistingSalt() {
            byte[] existingSalt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(Base64.getEncoder().encodeToString(existingSalt));

            saltManager.initialize();

            assertArrayEquals(existingSalt, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
        }

        @Test
        @DisplayName("generates new salt for fresh deployment (no DEKs) with an insert-if-absent, never an overwrite")
        void freshDeployment() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of());

            saltManager.initialize();

            byte[] salt = saltManager.getSalt();
            assertEquals(16, salt.length);
            assertFalse(saltManager.isUsingLegacySalt());
            verify(persistence).putMetaValueIfAbsent(eq(VaultSaltManager.SALT_META_KEY), anyString());
            verify(persistence, never()).setMetaValue(anyString(), anyString());
        }

        /**
         * H6a: two replicas booting against an empty database. The one that loses the
         * insert must derive its KEK from the winner's salt — keeping its own used to
         * make every DEK it wrapped unreadable at its next restart.
         */
        @Test
        @DisplayName("a replica that loses the race for a fresh salt adopts the winner's")
        void raceLoserAdoptsTheWinnersSalt() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of());
            when(persistence.putMetaValueIfAbsent(eq(VaultSaltManager.SALT_META_KEY), anyString()))
                    .thenReturn(Base64.getEncoder().encodeToString(OTHER_SALT));

            saltManager.initialize();

            assertArrayEquals(OTHER_SALT, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
        }

        /**
         * H6a: the first read found no salt, but by the time the DEKs were listed
         * another replica had written its salt and wrapped a DEK. That is a random-salt
         * deployment, not a legacy one.
         */
        @Test
        @DisplayName("a salt written between the first read and the DEK listing is adopted, not mistaken for legacy")
        void saltAppearingMidInitializationWins() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null, Base64.getEncoder().encodeToString(OTHER_SALT));
            when(persistence.listAllDeks()).thenReturn(List.of(new EncryptedDek()));

            saltManager.initialize();

            assertArrayEquals(OTHER_SALT, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
        }

        @Test
        @DisplayName("uses legacy salt for upgrade scenario (DEKs exist, no salt)")
        void upgradeLegacy() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of(new EncryptedDek()));

            saltManager.initialize();

            assertTrue(saltManager.isUsingLegacySalt());
            assertArrayEquals(LEGACY_SALT, saltManager.getSalt());
        }

        /**
         * H6a: a transient read failure used to fall back to the legacy salt — the
         * wrong KEK on any deployment that already has a random salt, so every DEK
         * created until the next restart was unreadable by everybody else.
         */
        @Test
        @DisplayName("a persistence failure fails the start instead of falling back to the legacy salt")
        void persistenceFailure() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenThrow(new PersistenceException("DB down"));

            var e = assertThrows(IllegalStateException.class, () -> saltManager.initialize());

            assertTrue(e.getMessage().contains("DB down"), e.getMessage());
            assertFalse(saltManager.isUsingLegacySalt());
            assertThrows(IllegalStateException.class, () -> saltManager.getSalt(), "no salt may be handed out after a failed initialisation");
        }

        @Test
        @DisplayName("a store with no metadata support fails the start rather than using a salt it cannot keep")
        void noMetadataStore() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of());
            when(persistence.putMetaValueIfAbsent(anyString(), anyString())).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> saltManager.initialize());
        }
    }

    @Nested
    @DisplayName("getSalt")
    class GetSaltTests {

        @Test
        @DisplayName("throws if not initialized")
        void throwsBeforeInit() {
            assertThrows(IllegalStateException.class, () -> saltManager.getSalt());
        }

        @Test
        @DisplayName("returns defensive copy")
        void defensiveCopy() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of());
            saltManager.initialize();

            byte[] salt1 = saltManager.getSalt();
            byte[] salt2 = saltManager.getSalt();
            assertNotSame(salt1, salt2);
            assertArrayEquals(salt1, salt2);
        }
    }

    @Nested
    @DisplayName("pending salt")
    class PendingSaltTests {

        /**
         * H6c: the salt a legacy-salt KEK rotation migrates to is persisted before
         * anything is wrapped under it, and a retry reuses it — so it derives the same
         * new KEK the interrupted run already wrapped some DEKs under.
         */
        @Test
        @DisplayName("reservePendingSalt reuses a pending salt an interrupted rotation persisted")
        void reusesExistingPendingSalt() {
            when(persistence.putMetaValueIfAbsent(eq(VaultSaltManager.PENDING_SALT_META_KEY), anyString()))
                    .thenReturn(Base64.getEncoder().encodeToString(OTHER_SALT));

            assertArrayEquals(OTHER_SALT, saltManager.reservePendingSalt());
        }

        @Test
        @DisplayName("reservePendingSalt persists a fresh salt when there is none")
        void persistsFreshPendingSalt() {
            byte[] reserved = saltManager.reservePendingSalt();

            assertEquals(16, reserved.length);
            verify(persistence).putMetaValueIfAbsent(eq(VaultSaltManager.PENDING_SALT_META_KEY), eq(Base64.getEncoder().encodeToString(reserved)));
        }

        @Test
        @DisplayName("getPendingSalt reads it back, or null")
        void readsPendingSalt() {
            assertNull(saltManager.getPendingSalt());
            when(persistence.getMetaValue(VaultSaltManager.PENDING_SALT_META_KEY)).thenReturn(Base64.getEncoder().encodeToString(OTHER_SALT));
            assertArrayEquals(OTHER_SALT, saltManager.getPendingSalt());
        }
    }

    @Nested
    @DisplayName("migrateSalt")
    class MigrateSaltTests {

        @Test
        @DisplayName("persists the salt if absent, clears the pending marker and switches to it")
        void migratesSuccessfully() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of(new EncryptedDek()));
            saltManager.initialize();
            assertTrue(saltManager.isUsingLegacySalt());

            byte[] newSalt = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16};
            saltManager.migrateSalt(newSalt);

            assertArrayEquals(newSalt, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
            verify(persistence).putMetaValueIfAbsent(VaultSaltManager.SALT_META_KEY, Base64.getEncoder().encodeToString(newSalt));
            verify(persistence).deleteMetaValue(VaultSaltManager.PENDING_SALT_META_KEY);
        }

        @Test
        @DisplayName("refuses when a different salt was persisted meanwhile, and keeps the pending marker")
        void refusesADifferentPersistedSalt() {
            when(persistence.putMetaValueIfAbsent(eq(VaultSaltManager.SALT_META_KEY), anyString()))
                    .thenReturn(Base64.getEncoder().encodeToString(OTHER_SALT));

            assertThrows(IllegalStateException.class,
                    () -> saltManager.migrateSalt(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}));
            verify(persistence, never()).deleteMetaValue(anyString());
        }

        @Test
        @DisplayName("rejects null salt")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> saltManager.migrateSalt(null));
        }

        @Test
        @DisplayName("rejects salt shorter than 8 bytes")
        void rejectsTooShort() {
            assertThrows(IllegalArgumentException.class, () -> saltManager.migrateSalt(new byte[]{1, 2, 3}));
        }
    }
}
