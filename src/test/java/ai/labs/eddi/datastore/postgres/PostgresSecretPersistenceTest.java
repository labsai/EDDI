/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.persistence.PostgresSecretPersistence;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresSecretPersistence} using Testcontainers.
 * <p>
 * Tests run against a real PostgreSQL instance. Named *IT.java so Maven
 * Failsafe picks them up with {@code mvn verify -DskipITs=false}.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresSecretPersistence IT")
class PostgresSecretPersistenceTest extends PostgresTestBase {

    private static PostgresSecretPersistence persistence;
    private static DataSource ds;

    @BeforeAll
    static void initPersistence() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        persistence = new PostgresSecretPersistence(dsInstance);
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        // Tables are created lazily by ensureSchema() on first call.
        // After the first test runs, tables exist and we can truncate.
        try {
            truncateTables(ds, "secret_vault_secrets", "secret_vault_deks", "secret_vault_meta");
        } catch (SQLException e) {
            // Tables don't exist yet on the very first test — that's fine,
            // ensureSchema() will create them.
        }
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
    class SecretsCrud {

        @Test
        @DisplayName("upsert + find round-trip")
        void upsertAndFind() {
            var secret = createSecret("tenant1", "API_KEY", "enc_val_1", "iv1", "dek1");
            persistence.upsertSecret(secret);

            Optional<EncryptedSecret> found = persistence.findSecret("tenant1", "API_KEY");
            assertTrue(found.isPresent());
            assertEquals("tenant1", found.get().getTenantId());
            assertEquals("API_KEY", found.get().getKeyName());
            assertEquals("enc_val_1", found.get().getEncryptedValue());
            assertEquals("iv1", found.get().getIv());
            assertEquals("dek1", found.get().getDekId());
        }

        @Test
        @DisplayName("upsert existing — updates value")
        void upsertExisting() {
            var secret = createSecret("tenant1", "API_KEY", "old_value", "iv1", "dek1");
            persistence.upsertSecret(secret);

            // Update with new value
            var updated = createSecret("tenant1", "API_KEY", "new_value", "iv2", "dek1");
            persistence.upsertSecret(updated);

            Optional<EncryptedSecret> found = persistence.findSecret("tenant1", "API_KEY");
            assertTrue(found.isPresent());
            assertEquals("new_value", found.get().getEncryptedValue());
            assertEquals("iv2", found.get().getIv());
        }

        @Test
        @DisplayName("find non-existent — returns empty")
        void findNonExistent() {
            Optional<EncryptedSecret> found = persistence.findSecret("ghost", "ghost_key");
            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("delete existing — returns true")
        void deleteExisting() {
            persistence.upsertSecret(createSecret("tenant1", "TO_DELETE", "val", "iv", "dek"));
            boolean deleted = persistence.deleteSecret("tenant1", "TO_DELETE");
            assertTrue(deleted);
            assertTrue(persistence.findSecret("tenant1", "TO_DELETE").isEmpty());
        }

        @Test
        @DisplayName("delete non-existent — returns false")
        void deleteNonExistent() {
            boolean deleted = persistence.deleteSecret("ghost", "ghost_key");
            assertFalse(deleted);
        }

        @Test
        @DisplayName("listSecretsByTenant — returns all secrets for tenant")
        void listByTenant() {
            persistence.upsertSecret(createSecret("tenant1", "KEY_A", "a", "iv", "dek"));
            persistence.upsertSecret(createSecret("tenant1", "KEY_B", "b", "iv", "dek"));
            persistence.upsertSecret(createSecret("tenant2", "KEY_C", "c", "iv", "dek"));

            List<EncryptedSecret> tenant1Secrets = persistence.listSecretsByTenant("tenant1");
            assertEquals(2, tenant1Secrets.size());

            List<EncryptedSecret> tenant2Secrets = persistence.listSecretsByTenant("tenant2");
            assertEquals(1, tenant2Secrets.size());
        }

        @Test
        @DisplayName("allowedAgents — JSONB round-trip preserves list")
        void allowedAgentsJsonb() {
            var secret = createSecret("tenant1", "RESTRICTED", "val", "iv", "dek");
            secret.setAllowedAgents(List.of("agent-1", "agent-2"));
            persistence.upsertSecret(secret);

            var found = persistence.findSecret("tenant1", "RESTRICTED");
            assertTrue(found.isPresent());
            assertEquals(List.of("agent-1", "agent-2"), found.get().getAllowedAgents());
        }

        @Test
        @DisplayName("null allowedAgents — defaults to [*]")
        void nullAllowedAgents() {
            var secret = createSecret("tenant1", "OPEN", "val", "iv", "dek");
            secret.setAllowedAgents(null);
            persistence.upsertSecret(secret);

            var found = persistence.findSecret("tenant1", "OPEN");
            assertTrue(found.isPresent());
            assertEquals(List.of("*"), found.get().getAllowedAgents());
        }

        @Test
        @DisplayName("timestamps round-trip")
        void timestampsRoundTrip() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var secret = createSecret("tenant1", "TIMED", "val", "iv", "dek");
            secret.setCreatedAt(now);
            secret.setLastAccessedAt(now);
            secret.setLastRotatedAt(now);
            persistence.upsertSecret(secret);

            var found = persistence.findSecret("tenant1", "TIMED");
            assertTrue(found.isPresent());
            // Postgres TIMESTAMP has microsecond precision — compare truncated
            assertEquals(now.truncatedTo(ChronoUnit.MILLIS),
                    found.get().getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
        }
    }

    // ─── DEKs ───────────────────────────────────────────────────

    @Nested
    @DisplayName("DEK CRUD")
    class DekCrud {

        @Test
        @DisplayName("upsert + find round-trip")
        void upsertAndFind() {
            var dek = new EncryptedDek(null, "tenant1", "enc_dek_data", "dek_iv", Instant.now());
            persistence.upsertDek(dek);

            Optional<EncryptedDek> found = persistence.findDek("tenant1");
            assertTrue(found.isPresent());
            assertEquals("tenant1", found.get().getTenantId());
            assertEquals("enc_dek_data", found.get().getEncryptedDek());
            assertEquals("dek_iv", found.get().getIv());
            assertNotNull(found.get().getId()); // auto-generated
        }

        @Test
        @DisplayName("upsert existing — updates DEK value")
        void upsertExisting() {
            persistence.upsertDek(new EncryptedDek(null, "tenant1", "old_dek", "iv1", Instant.now()));
            persistence.upsertDek(new EncryptedDek(null, "tenant1", "new_dek", "iv2", Instant.now()));

            var found = persistence.findDek("tenant1");
            assertTrue(found.isPresent());
            assertEquals("new_dek", found.get().getEncryptedDek());
            assertEquals("iv2", found.get().getIv());
        }

        @Test
        @DisplayName("find non-existent — returns empty")
        void findNonExistent() {
            assertTrue(persistence.findDek("ghost_tenant").isEmpty());
        }

        @Test
        @DisplayName("delete — removes DEK")
        void deleteDek() {
            persistence.upsertDek(new EncryptedDek(null, "tenant_del", "dek", "iv", Instant.now()));
            persistence.deleteDek("tenant_del");
            assertTrue(persistence.findDek("tenant_del").isEmpty());
        }

        @Test
        @DisplayName("listAllDeks — returns all DEKs")
        void listAll() {
            persistence.upsertDek(new EncryptedDek(null, "t1", "d1", "iv1", Instant.now()));
            persistence.upsertDek(new EncryptedDek(null, "t2", "d2", "iv2", Instant.now()));

            List<EncryptedDek> all = persistence.listAllDeks();
            assertEquals(2, all.size());
        }
    }

    // ─── Metadata ───────────────────────────────────────────────

    @Nested
    @DisplayName("Metadata CRUD")
    class MetadataCrud {

        @Test
        @DisplayName("set + get round-trip")
        void setAndGet() {
            persistence.setMetaValue("vault.version", "2");
            assertEquals("2", persistence.getMetaValue("vault.version"));
        }

        @Test
        @DisplayName("set existing — upserts value")
        void setExistingUpserts() {
            persistence.setMetaValue("vault.version", "1");
            persistence.setMetaValue("vault.version", "3");
            assertEquals("3", persistence.getMetaValue("vault.version"));
        }

        @Test
        @DisplayName("set-if-absent — first value wins, a later one never replaces it")
        void setIfAbsentKeepsFirst() {
            assertEquals("first", persistence.setMetaValueIfAbsent("once.key", "first"));
            assertEquals("first", persistence.setMetaValueIfAbsent("once.key", "second"));
            assertEquals("first", persistence.getMetaValue("once.key"));
        }

        @Test
        @DisplayName("get non-existent — returns null")
        void getNonExistent() {
            assertNull(persistence.getMetaValue("nonexistent.key"));
        }
    }

    // ─── Schema ─────────────────────────────────────────────────

    @Test
    @DisplayName("ensureSchema is idempotent — second call does not throw")
    void ensureSchemaIdempotent() {
        // First call creates tables (implicitly via any operation)
        persistence.getMetaValue("test");
        // Second call should be a no-op
        assertDoesNotThrow(() -> persistence.getMetaValue("test2"));
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static EncryptedSecret createSecret(String tenant, String key, String value, String iv, String dekId) {
        var secret = new EncryptedSecret();
        secret.setTenantId(tenant);
        secret.setKeyName(key);
        secret.setEncryptedValue(value);
        secret.setIv(iv);
        secret.setDekId(dekId);
        secret.setChecksum("sha256_check");
        secret.setDescription("Test secret");
        secret.setAllowedAgents(List.of("*"));
        secret.setCreatedAt(Instant.now());
        return secret;
    }
}
