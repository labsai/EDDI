package ai.labs.eddi.secrets.crypto;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link VaultSaltManager} — salt lifecycle (load, generate, migrate,
 * legacy fallback).
 */
@DisplayName("VaultSaltManager")
class VaultSaltManagerTest {

    private ISecretPersistence persistence;
    private VaultSaltManager saltManager;

    @BeforeEach
    void setUp() {
        persistence = mock(ISecretPersistence.class);
        saltManager = new VaultSaltManager(persistence);
    }

    @Nested
    @DisplayName("initialize")
    class InitializeTests {

        @Test
        @DisplayName("loads existing salt from persistence")
        void loadsExistingSalt() {
            byte[] existingSalt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY))
                    .thenReturn(Base64.getEncoder().encodeToString(existingSalt));

            saltManager.initialize();

            assertArrayEquals(existingSalt, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
        }

        @Test
        @DisplayName("generates new salt for fresh deployment (no DEKs)")
        void freshDeployment() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of());

            saltManager.initialize();

            byte[] salt = saltManager.getSalt();
            assertEquals(16, salt.length);
            assertFalse(saltManager.isUsingLegacySalt());
            verify(persistence).setMetaValue(eq(VaultSaltManager.SALT_META_KEY), anyString());
        }

        @Test
        @DisplayName("uses legacy salt for upgrade scenario (DEKs exist, no salt)")
        void upgradeLegacy() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of(new EncryptedDek()));

            saltManager.initialize();

            assertTrue(saltManager.isUsingLegacySalt());
            assertNotNull(saltManager.getSalt());
        }

        @Test
        @DisplayName("falls back to legacy salt on persistence failure")
        void persistenceFailure() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY))
                    .thenThrow(new PersistenceException("DB down"));

            saltManager.initialize();

            assertTrue(saltManager.isUsingLegacySalt());
            assertNotNull(saltManager.getSalt());
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
    @DisplayName("migrateSalt")
    class MigrateSaltTests {

        @Test
        @DisplayName("updates salt and persists it")
        void migratesSuccessfully() {
            when(persistence.getMetaValue(VaultSaltManager.SALT_META_KEY)).thenReturn(null);
            when(persistence.listAllDeks()).thenReturn(List.of(new EncryptedDek()));
            saltManager.initialize();
            assertTrue(saltManager.isUsingLegacySalt());

            byte[] newSalt = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16};
            saltManager.migrateSalt(newSalt);

            assertArrayEquals(newSalt, saltManager.getSalt());
            assertFalse(saltManager.isUsingLegacySalt());
            verify(persistence).setMetaValue(eq(VaultSaltManager.SALT_META_KEY), anyString());
        }

        @Test
        @DisplayName("rejects null salt")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> saltManager.migrateSalt(null));
        }

        @Test
        @DisplayName("rejects salt shorter than 8 bytes")
        void rejectsTooShort() {
            assertThrows(IllegalArgumentException.class,
                    () -> saltManager.migrateSalt(new byte[]{1, 2, 3}));
        }
    }
}
