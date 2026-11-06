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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 * and bounded by {@link #MAX_POOLS}.
 * <p>
 * <b>A store never holds a pool.</b> {@link #get} returns a handle that looks
 * its pool up on every {@code getConnection()}, so a size eviction cannot
 * strand a store, a cached one or one an ingestion run is using: its next
 * borrow opens a new pool for the same login. The lookup also counts as use of
 * the pool, so a pool that stores are working with is the last the cache
 * evicts. An evicted pool with connections still borrowed is not closed under
 * them: it is retired, keeps serving the borrowers it has, and is closed on a
 * later lookup once all of them are returned. {@link #closeAll} closes every
 * pool, retired ones included.
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

    /** What a pool is opened with. The password is never logged. */
    record Login(String host, int port, String database, String user, String password, int maxSize) {

        @Override
        public String toString() {
            return "Login[" + host + ":" + port + "/" + database + ", user=" + user + "]";
        }
    }

    private final Function<Login, AgroalDataSource> factory;

    /** Evicted pools that still had connections borrowed. */
    private final Set<AgroalDataSource> retired = ConcurrentHashMap.newKeySet();

    private final Cache<String, AgroalDataSource> pools;

    PgVectorConnectionPools() {
        this(PgVectorConnectionPools::create);
    }

    /** For tests: {@code factory} opens a pool for a login. */
    PgVectorConnectionPools(Function<Login, AgroalDataSource> factory) {
        this.factory = factory;
        this.pools = Caffeine.newBuilder()
                .maximumSize(MAX_POOLS)
                .executor(Runnable::run)
                .removalListener((String key, AgroalDataSource pool, RemovalCause cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        LOGGER.warnf("Evicting a pgvector connection pool because more than %d distinct PostgreSQL logins are in use by "
                                + "knowledge bases; its stores reopen it on their next use. Reduce the number of distinct "
                                + "host/database/user combinations across knowledge bases.", MAX_POOLS);
                        retire(pool);
                    } else {
                        closeQuietly(pool);
                    }
                })
                .build();
    }

    /**
     * A data source for a login. It holds no pool: each {@code getConnection()}
     * borrows from the login's current pool, opening one if there is none.
     *
     * @param maxSize
     *            the most connections the pool opens; applies when the pool is
     *            created
     */
    DataSource get(String host, int port, String database, String user, String password, int maxSize) {
        Login login = new Login(host, port, database, user, password, maxSize);
        String key = EmbeddingStoreFactory.connectionKey(String.join("\u0000", host, Integer.toString(port), database, user, password));
        return new PgVectorPoolHandle(this, key, login);
    }

    /**
     * Borrows a connection from the login's pool. If the pool was evicted between
     * the lookup and the borrow and has been closed, the borrow is retried once on
     * the pool that replaced it.
     */
    Connection connection(String key, Login login) throws SQLException {
        closeIdleRetired();
        AgroalDataSource pool = pool(key, login);
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            if (pools.getIfPresent(key) == pool) {
                throw e;
            }
            return pool(key, login).getConnection();
        }
    }

    private AgroalDataSource pool(String key, Login login) {
        return pools.get(key, k -> factory.apply(login));
    }

    /** Number of open pools, retired ones included — for tests. */
    long size() {
        pools.cleanUp();
        closeIdleRetired();
        return pools.estimatedSize() + retired.size();
    }

    /** Evicted pools waiting for their borrowed connections — for tests. */
    int retiredCount() {
        return retired.size();
    }

    /** Closes every pool, including those with connections borrowed. */
    void closeAll() {
        pools.invalidateAll();
        pools.cleanUp();
        for (AgroalDataSource pool : retired) {
            if (retired.remove(pool)) {
                closeQuietly(pool);
            }
        }
    }

    private void retire(AgroalDataSource pool) {
        if (pool == null) {
            return;
        }
        if (borrowed(pool) == 0) {
            closeQuietly(pool);
        } else {
            retired.add(pool);
        }
    }

    private void closeIdleRetired() {
        if (retired.isEmpty()) {
            return;
        }
        for (AgroalDataSource pool : retired) {
            if (borrowed(pool) == 0 && retired.remove(pool)) {
                closeQuietly(pool);
            }
        }
    }

    /** Connections borrowed and not yet returned; needs metrics enabled. */
    private static long borrowed(AgroalDataSource pool) {
        try {
            return pool.getMetrics().activeCount();
        } catch (RuntimeException e) {
            return 0;
        }
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

    private static AgroalDataSource create(Login login) {
        var configuration = new AgroalDataSourceConfigurationSupplier()
                // activeCount() tells an evicted pool with borrowed connections from an idle
                // one
                .metricsEnabled(true)
                .connectionPoolConfiguration(pool -> pool
                        .initialSize(0)
                        .minSize(0)
                        .maxSize(Math.max(1, login.maxSize()))
                        .acquisitionTimeout(ACQUISITION_TIMEOUT)
                        .reapTimeout(REAP_TIMEOUT)
                        .maxLifetime(MAX_LIFETIME)
                        .connectionFactoryConfiguration(factory -> factory
                                .connectionProviderClass(Driver.class)
                                .jdbcUrl(jdbcUrl(login.host(), login.port(), login.database()))
                                .principal(new NamePrincipal(login.user()))
                                .credential(new SimplePassword(login.password()))
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
