/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import jakarta.enterprise.inject.Instance;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared Testcontainers base for PostgreSQL adapter integration tests.
 * <p>
 * Starts a single PostgreSQL container per JVM (static lifecycle) and provides
 * a real {@link DataSource} and a mock {@link Instance<DataSource>} that
 * delegates to it. No Quarkus CDI augmentation needed — the adapters only
 * require a DataSource.
 * <p>
 * This abstract base class is intended to be extended by concrete integration
 * tests that are collected by Maven Failsafe according to the project's test
 * naming or plugin configuration. This class itself contains no tests and is
 * not executed directly.
 *
 * @since 6.0.0
 */
public abstract class PostgresTestBase {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eddi_test")
            .withUsername("test")
            .withPassword("test");

    static {
        PG.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    /**
     * Returns a real JDBC DataSource pointing at the shared Testcontainer.
     */
    protected static DataSource createDataSource() {
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    /**
     * Creates a minimal {@link Instance<DataSource>} wrapper that returns our test
     * DataSource. Only {@code get()} is implemented — all other Instance methods
     * throw UnsupportedOperationException.
     */
    protected static Instance<DataSource> createDataSourceInstance() {
        DataSource ds = createDataSource();
        return new SimpleDataSourceInstance(ds);
    }

    /**
     * Truncates the given tables to ensure test isolation between methods.
     */
    protected static void truncateTables(DataSource ds, String... tableNames) throws SQLException {
        try (Connection conn = ds.getConnection(); var stmt = conn.createStatement()) {
            for (String table : tableNames) {
                stmt.execute("TRUNCATE TABLE " + table + " CASCADE");
            }
        }
    }

    /**
     * Minimal Instance<DataSource> implementation that only supports get().
     */
    private static class SimpleDataSourceInstance implements Instance<DataSource> {
        private final DataSource ds;

        SimpleDataSourceInstance(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public DataSource get() {
            return ds;
        }

        @Override
        public Instance<DataSource> select(java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override
        public <U extends DataSource> Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override
        public <U extends DataSource> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype,
                                                         java.lang.annotation.Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean isUnsatisfied() {
            return false;
        }
        @Override
        public boolean isAmbiguous() {
            return false;
        }
        @Override
        public boolean isResolvable() {
            return true;
        }
        @Override
        public void destroy(DataSource instance) {
        }
        @Override
        public Handle<DataSource> getHandle() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Iterable<? extends Handle<DataSource>> handles() {
            throw new UnsupportedOperationException();
        }
        @Override
        public java.util.Iterator<DataSource> iterator() {
            return java.util.List.of(ds).iterator();
        }
    }
}
