/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.jboss.logging.Logger;
import org.postgresql.Driver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

/**
 * Connection pools for pgvector knowledge bases, one per PostgreSQL server and
 * login — (host, port, database, user, password) — shared by every knowledge
 * base that uses it.
 * <p>
 * langchain4j's host/port builder wraps a {@code PGSimpleDataSource}, which
 * opens a new physical connection — TCP, TLS, authentication — for every
 * search, insert and delete, and closes it again. A pool keeps them.
 * <p>
 * Each pool starts empty and returns to empty when idle ({@code minSize} 0,
 * idle connections reaped after {@link #REAP_TIMEOUT}), so a pool nothing uses
 * any more — after a password rotation, say, which produces a new key — holds
 * no connection. The cache is keyed on a digest, never on the password itself,
 * and bounded the same way as the MongoDB client cache: a size eviction closes
 * the pool, which a live store built on it would feel, so the bound is larger
 * than the store cache and should be unreachable in normal operation.
 */
final class PgVectorConnectionPools {

    private static final Logger LOGGER = Logger.getLogger(PgVectorConnectionPools.class);

    static final int MAX_POOLS = EmbeddingStoreFactory.MAX_STORES + 14;

    /** An idle connection above {@code minSize} is closed after this long. */
    static final Duration REAP_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Connections are replaced after this long, so a server-side change (a
     * failover, a rotated certificate) reaches every pool.
     */
    static final Duration MAX_LIFETIME = Duration.ofMinutes(30);

    /** How long a caller waits for a free connection, or for a new one to open. */
    static final Duration ACQUISITION_TIMEOUT = Duration.ofSeconds(30);

    private final Cache<String, AgroalDataSource> pools = Caffeine.newBuilder()
            .maximumSize(MAX_POOLS)
            .removalListener((String key, AgroalDataSource pool, RemovalCause cause) -> {
                if (cause == RemovalCause.SIZE) {
                    LOGGER.warnf("Closing a pgvector connection pool because more than %d distinct PostgreSQL logins are in use by "
                            + "knowledge bases. A store still using it will start failing; reduce the number of distinct "
                            + "host/database/user combinations across knowledge bases.", MAX_POOLS);
                }
                closeQuietly(pool);
            })
            .build();

    /**
     * The pool for a login, created on first use.
     *
     * @param maxSize
     *            the most connections the pool opens; applies when the pool is
     *            created
     */
    DataSource get(String host, int port, String database, String user, String password, int maxSize) {
        String key = EmbeddingStoreFactory.connectionKey(String.join("\u0000", host, Integer.toString(port), database, user, password));
        return pools.get(key, k -> create(host, port, database, user, password, maxSize));
    }

    /** Number of open pools — for tests. */
    long size() {
        pools.cleanUp();
        return pools.estimatedSize();
    }

    /** Closes every pool. A store still holding one fails on its next use. */
    void closeAll() {
        pools.invalidateAll();
        pools.cleanUp();
    }

    /**
     * The JDBC URL for a server. The host has been checked to be one plain hostname
     * or IP literal ({@code EmbeddingStoreFactory.requirePlainHost}); an IPv6
     * literal is bracketed. The database name is URL-encoded — pgjdbc decodes the
     * path — so a name containing {@code ?} or {@code &} cannot add driver
     * properties to the URL.
     */
    static String jdbcUrl(String host, int port, String database) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:postgresql://" + h + ":" + port + "/" + URLEncoder.encode(database, StandardCharsets.UTF_8);
    }

    private static AgroalDataSource create(String host, int port, String database, String user, String password, int maxSize) {
        var configuration = new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(pool -> pool
                        .initialSize(0)
                        .minSize(0)
                        .maxSize(Math.max(1, maxSize))
                        .acquisitionTimeout(ACQUISITION_TIMEOUT)
                        .reapTimeout(REAP_TIMEOUT)
                        .maxLifetime(MAX_LIFETIME)
                        .connectionFactoryConfiguration(factory -> factory
                                .connectionProviderClass(Driver.class)
                                .jdbcUrl(jdbcUrl(host, port, database))
                                .principal(new NamePrincipal(user))
                                .credential(new SimplePassword(password))
                                .jdbcProperty("ApplicationName", "EDDI pgvector")
                                .autoCommit(true)));
        try {
            return AgroalDataSource.from(configuration);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the pgvector connection pool: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(AgroalDataSource pool) {
        if (pool == null) {
            return;
        }
        try {
            pool.close();
        } catch (RuntimeException e) {
            LOGGER.warnf("Failed to close a pgvector connection pool: %s", e.getMessage());
        }
    }
}
