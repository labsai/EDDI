/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first requests against a database that has no tables yet arrive at the
 * same moment (several stores, several threads). {@code CREATE TABLE IF NOT
 * EXISTS} is not safe against a concurrent copy of itself, so each store used
 * to race its neighbours for the same table; the losers failed with
 * {@code duplicate key value violates unique constraint "pg_type_typname_nsp_index"}
 * and several stores swallowed it. Runs against a fresh, empty schema.
 */
@DisplayName("PostgreSQL lazy schema creation on a fresh database")
class PostgresSchemaConcurrencyIT extends PostgresTestBase {

    /** A data source on a schema nobody has created a table in. */
    private static PGSimpleDataSource freshSchema() throws Exception {
        String schema = "race_" + UUID.randomUUID().toString().replace("-", "");
        var base = (PGSimpleDataSource) createDataSource();
        try (Connection c = base.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        var ds = new PGSimpleDataSource();
        ds.setUrl(base.getUrl());
        ds.setUser(base.getUser());
        ds.setPassword(base.getPassword());
        ds.setCurrentSchema(schema);
        return ds;
    }

    @Test
    @DisplayName("many stores and threads creating their tables at once all succeed")
    void concurrentFirstUse() throws Exception {
        PGSimpleDataSource ds = freshSchema();
        Instance<DataSource> instance = createDataSourceInstance(ds);
        IJsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;

        int rounds = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            int n = i;
            tasks.add(() -> new PostgresResourceStorage<>(ds, "collection_" + n, json, type));
            tasks.add(() -> new PostgresUserMemoryStore(instance).getAllEntries("user-" + n));
            tasks.add(() -> new PostgresDatabaseLogs(instance).getLogs(null, null, null, null, null, null, 0, 1));
            tasks.add(() -> new PostgresAuditStore(instance, json).getEntries("conv-" + n, 0, 1));
            tasks.add(() -> new PostgresDeploymentStorage(instance).readDeploymentInfos(null));
        }

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        for (Future<Object> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // every table exists, exactly once
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                    + "AND table_name IN ('resources', 'resources_history', 'usermemories', 'database_logs', 'audit_ledger', 'deployments')");
            assertTrue(rs.next());
            assertEquals(6, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("running the same creation many times at once under the lock does not fail")
    void sameCreationManyTimes() throws Exception {
        PGSimpleDataSource ds = freshSchema();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                PostgresSchema.run(ds, "race", stmt -> {
                    stmt.execute("CREATE TABLE IF NOT EXISTS race_table (id INT PRIMARY KEY, data JSONB)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_race_table_data ON race_table USING GIN (data)");
                });
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }
}
