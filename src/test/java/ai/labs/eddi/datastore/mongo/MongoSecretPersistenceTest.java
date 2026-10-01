/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.persistence.MongoSecretPersistence;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        // The drop took the unique (tenantId, keyName) index with it. Rebuilding the
        // persistence recreates it, so each test runs under the index production has —
        // without it insertSecretIfAbsent has nothing to conflict against.
        persistence = new MongoSecretPersistence(getDatabase());
    }

    // ─── Create-if-absent (#700) ────────────────────────────────

    @Nested
    @DisplayName("insertSecretIfAbsent")
    class InsertIfAbsent {

        private static final int WRITERS = 8;
        private static final int ROUNDS = 25;

        @Test
        @DisplayName("inserts a missing secret and reports it")
        void insertsWhenAbsent() {
            assertTrue(persistence.insertSecretIfAbsent(createSecret("t1", "k1", "v1", "iv", "d1")));

            assertEquals("v1", persistence.findSecret("t1", "k1").orElseThrow().getEncryptedValue());
        }

        @Test
        @DisplayName("an existing secret is reported and left exactly as it was")
        void leavesAnExistingSecretAlone() {
            persistence.upsertSecret(createSecret("t1", "k1", "original", "iv-original", "d1"));

            assertFalse(persistence.insertSecretIfAbsent(createSecret("t1", "k1", "intruder", "iv-intruder", "d2")));

            var found = persistence.findSecret("t1", "k1").orElseThrow();
            assertEquals("original", found.getEncryptedValue());
            assertEquals("iv-original", found.getIv());
            assertEquals("d1", found.getDekId());
        }

        @Test
        @DisplayName("the same key name in another tenant is a different secret")
        void tenantsAreIndependent() {
            assertTrue(persistence.insertSecretIfAbsent(createSecret("t1", "k1", "v1", "iv", "d1")));
            assertTrue(persistence.insertSecretIfAbsent(createSecret("t2", "k1", "v2", "iv", "d1")));
        }

        @Test
        @DisplayName("after an insert, upsertSecret still replaces (rotation keeps working)")
        void upsertStillReplaces() {
            persistence.insertSecretIfAbsent(createSecret("t1", "k1", "v1", "iv", "d1"));

            persistence.upsertSecret(createSecret("t1", "k1", "v2", "iv", "d1"));

            assertEquals("v2", persistence.findSecret("t1", "k1").orElseThrow().getEncryptedValue());
        }

        @Test
        @DisplayName("concurrent inserts of one key: exactly one wins, and its value is the one stored")
        void concurrentInsertsHaveOneWinner() throws Exception {
            for (int round = 0; round < ROUNDS; round++) {
                String key = "contended-" + round;
                var winners = new ConcurrentLinkedQueue<Integer>();
                var pool = Executors.newFixedThreadPool(WRITERS);
                try {
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < WRITERS; i++) {
                        int writer = i;
                        futures.add(pool.submit(() -> {
                            start.await();
                            if (persistence.insertSecretIfAbsent(createSecret("t1", key, "value-" + writer, "iv", "d1"))) {
                                winners.add(writer);
                            }
                            return null;
                        }));
                    }
                    start.countDown();
                    for (var f : futures) {
                        f.get(30, TimeUnit.SECONDS);
                    }
                } finally {
                    pool.shutdownNow();
                }

                assertEquals(1, winners.size(), "round " + round + ": winners " + winners);
                assertEquals("value-" + winners.peek(), persistence.findSecret("t1", key).orElseThrow().getEncryptedValue(),
                        "round " + round + ": the stored value must be the winner's");
            }
        }
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

        @Test
        @DisplayName("set-if-absent — first value wins, a later one never replaces it")
        void setIfAbsentKeepsFirst() {
            assertEquals("first", persistence.setMetaValueIfAbsent("once.key", "first"));
            assertEquals("first", persistence.setMetaValueIfAbsent("once.key", "second"));
            assertEquals("first", persistence.getMetaValue("once.key"));
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
