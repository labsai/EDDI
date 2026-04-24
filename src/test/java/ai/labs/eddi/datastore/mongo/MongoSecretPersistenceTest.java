/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.persistence.MongoSecretPersistence;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoSecretPersistence} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoSecretPersistence IT")
class MongoSecretPersistenceTest extends MongoTestBase {

    private static MongoSecretPersistence persistence;

    @BeforeAll
    static void init() {
        persistence = new MongoSecretPersistence(getDatabase());
    }

    @BeforeEach
    void clean() {
        dropCollections("secretvault_secrets", "secretvault_deks", "secretvault_meta");
    }

    // ─── Secrets ────────────────────────────────────────────────

    @Nested
    @DisplayName("Secrets CRUD")
    class Secrets {

        @Test
        @DisplayName("upsert + find round-trip")
        void upsertAndFind() {
            var secret = createSecret("tenant1", "api_key", "encrypted-val", "iv-data", "dek1");
            persistence.upsertSecret(secret);

            var found = persistence.findSecret("tenant1", "api_key");
            assertTrue(found.isPresent());
            assertEquals("encrypted-val", found.get().getEncryptedValue());
        }

        @Test
        @DisplayName("find non-existent — returns empty")
        void findNonExistent() {
            assertTrue(persistence.findSecret("ghost", "key").isEmpty());
        }

        @Test
        @DisplayName("upsert existing — updates value")
        void upsertExisting() {
            persistence.upsertSecret(createSecret("t1", "k1", "v1", "iv", "d1"));
            persistence.upsertSecret(createSecret("t1", "k1", "v2", "iv2", "d1"));

            var found = persistence.findSecret("t1", "k1");
            assertEquals("v2", found.get().getEncryptedValue());
        }

        @Test
        @DisplayName("deleteSecret — removes and returns true")
        void delete() {
            persistence.upsertSecret(createSecret("t1", "k1", "v", "iv", "d1"));
            assertTrue(persistence.deleteSecret("t1", "k1"));
            assertTrue(persistence.findSecret("t1", "k1").isEmpty());
        }

        @Test
        @DisplayName("deleteSecret non-existent — returns false")
        void deleteNonExistent() {
            assertFalse(persistence.deleteSecret("ghost", "key"));
        }

        @Test
        @DisplayName("listSecretsByTenant — filters by tenant")
        void listByTenant() {
            persistence.upsertSecret(createSecret("t1", "k1", "v1", "iv", "d1"));
            persistence.upsertSecret(createSecret("t1", "k2", "v2", "iv", "d1"));
            persistence.upsertSecret(createSecret("t2", "k3", "v3", "iv", "d2"));

            assertEquals(2, persistence.listSecretsByTenant("t1").size());
            assertEquals(1, persistence.listSecretsByTenant("t2").size());
        }
    }

    // ─── DEKs ───────────────────────────────────────────────────

    @Nested
    @DisplayName("DEK CRUD")
    class Deks {

        @Test
        @DisplayName("upsert + find DEK round-trip")
        void upsertAndFind() {
            var dek = new EncryptedDek(null, "tenant1", "enc-dek-data", "dek-iv", Instant.now());
            persistence.upsertDek(dek);

            var found = persistence.findDek("tenant1");
            assertTrue(found.isPresent());
            assertEquals("enc-dek-data", found.get().getEncryptedDek());
        }

        @Test
        @DisplayName("find DEK non-existent — returns empty")
        void findNonExistent() {
            assertTrue(persistence.findDek("ghost").isEmpty());
        }

        @Test
        @DisplayName("delete DEK")
        void deleteDek() {
            persistence.upsertDek(new EncryptedDek(null, "t1", "enc", "iv", Instant.now()));
            persistence.deleteDek("t1");
            assertTrue(persistence.findDek("t1").isEmpty());
        }

        @Test
        @DisplayName("listAllDeks — returns all")
        void listAll() {
            persistence.upsertDek(new EncryptedDek(null, "t1", "e1", "iv1", Instant.now()));
            persistence.upsertDek(new EncryptedDek(null, "t2", "e2", "iv2", Instant.now()));

            assertEquals(2, persistence.listAllDeks().size());
        }
    }

    // ─── Metadata ───────────────────────────────────────────────

    @Nested
    @DisplayName("Metadata")
    class Meta {

        @Test
        @DisplayName("set + get meta value")
        void setAndGet() {
            persistence.setMetaValue("vault.salt", "random-salt");
            assertEquals("random-salt", persistence.getMetaValue("vault.salt"));
        }

        @Test
        @DisplayName("get non-existent — returns null")
        void getNonExistent() {
            assertNull(persistence.getMetaValue("nonexistent"));
        }

        @Test
        @DisplayName("set meta value — upsert on conflict")
        void upsertMeta() {
            persistence.setMetaValue("key", "v1");
            persistence.setMetaValue("key", "v2");
            assertEquals("v2", persistence.getMetaValue("key"));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static EncryptedSecret createSecret(String tenant, String key,
                                                String value, String iv, String dekId) {
        var secret = new EncryptedSecret();
        secret.setTenantId(tenant);
        secret.setKeyName(key);
        secret.setEncryptedValue(value);
        secret.setIv(iv);
        secret.setDekId(dekId);
        secret.setCreatedAt(Instant.now());
        secret.setAllowedAgents(List.of("*"));
        return secret;
    }
}
