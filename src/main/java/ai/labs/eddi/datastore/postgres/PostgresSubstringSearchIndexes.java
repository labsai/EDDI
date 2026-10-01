/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trigram indexes ({@code pg_trgm}) on the fields one collection searches by
 * substring, so a search such as the descriptor listing's search box does not
 * scan the collection.
 *
 * <h3>Why the indexes are built in the background</h3> A GIN trigram index
 * takes seconds per 100k rows to build — 300k descriptors took about 8 s for
 * five fields. They are built with {@code CREATE INDEX CONCURRENTLY}, which
 * does not block writes to the shared {@code resources} table, on a background
 * thread, so a large deployment neither waits at boot nor stops writing while
 * they are built. Until all of them are valid, {@link #isReady()} is
 * {@code false} and the search runs exactly as it would without them.
 *
 * <h3>Why readiness matters to the query</h3> PostgreSQL only uses these
 * indexes when it plans a search for its actual pattern. The JDBC driver
 * server-prepares a statement it executes repeatedly, and PostgreSQL then
 * switches to one cached generic plan — measured on 300k descriptors, that plan
 * gained nothing from the indexes (a miss took about a second with and without
 * them). {@link PostgresResourceStorage} therefore has a search planned per
 * execution once the indexes are ready, and only then: without them,
 * per-execution planning made a common term about 170× slower than the cached
 * plan, which happens to stop early on the date index.
 *
 * <h3>Concurrency and repair</h3> One builder at a time across every EDDI
 * instance, via an advisory lock; an instance that does not get it just checks
 * readiness later. An interrupted concurrent build leaves an INVALID index,
 * which {@code CREATE INDEX IF NOT EXISTS} would skip for ever while it still
 * costs every write — the builder drops and rebuilds such an index.
 *
 * <h3>Failure is not fatal</h3> The build starts once boot has settled and
 * retries a transient failure: on a live boot, a concurrent build running into
 * the schema setup of the other storages was aborted by PostgreSQL's deadlock
 * detection. If {@code CREATE EXTENSION pg_trgm} or an index build is refused
 * (a locked-down database role), a warning is logged once and search keeps
 * working unindexed — an index is an optimisation, never a correctness
 * requirement.
 */
final class PostgresSubstringSearchIndexes {

    private static final Logger LOGGER = Logger.getLogger(PostgresSubstringSearchIndexes.class);

    /**
     * How long a "not ready" answer is trusted before the catalogue is asked again.
     */
    private static final long RECHECK_INTERVAL_MILLIS = 60_000;

    /**
     * Advisory lock key shared by every instance building these indexes ("eddi" +
     * "trgm").
     */
    private static final long BUILD_LOCK_KEY = 0x65646469_7472676DL;

    private final DataSource dataSource;
    private final String collectionName;
    private final List<String> fields;
    private final List<String> indexNames;

    private volatile boolean ready;
    private volatile long lastCheckMillis;

    PostgresSubstringSearchIndexes(DataSource dataSource, String collectionName, List<String> fields) {
        this.dataSource = dataSource;
        this.collectionName = collectionName;
        this.fields = List.copyOf(fields);
        List<String> names = new ArrayList<>();
        for (String field : this.fields) {
            names.add(indexName(collectionName, field));
        }
        this.indexNames = List.copyOf(names);
    }

    /**
     * The searchable fields among {@code requested}: plain top-level keys only, as
     * the trigram index expression is {@code data ->> 'field'}.
     */
    static List<String> usableFields(String... requested) {
        List<String> usable = new ArrayList<>();
        for (String field : requested) {
            if (field != null && field.matches("[a-zA-Z0-9_]+")) {
                usable.add(field);
            }
        }
        return usable;
    }

    /**
     * The collection name is spliced into DDL, so it must be a plain
     * identifier-like name.
     */
    static boolean usableCollection(String collectionName) {
        return collectionName != null && collectionName.matches("[a-zA-Z0-9_.]+");
    }

    /**
     * Builds the indexes on a background thread, once boot has settled; returns at
     * once. See {@link #buildWithRetries}.
     */
    void buildInBackground(long startDelayMillis) {
        Thread.ofVirtual().name("pg-trgm-indexes-" + collectionName).start(() -> {
            if (sleep(startDelayMillis)) {
                buildWithRetries(RETRY_DELAYS_MILLIS);
            }
        });
    }

    /**
     * How long the build waits after boot. Every storage creates its schema and
     * btree indexes while the application starts; a concurrent build running into
     * those was aborted by PostgreSQL's deadlock detection on a live boot, leaving
     * an index INVALID.
     */
    static final long START_DELAY_MILLIS = 15_000;

    /** Waits before each retry of a build that failed transiently. */
    static final long[] RETRY_DELAYS_MILLIS = {30_000, 120_000, 600_000};

    /**
     * Builds, and retries a transient failure — a deadlock, a lock timeout, a
     * cancelled statement — after each of {@code retryDelaysMillis}. Each retry
     * repairs what the failed attempt left INVALID. A failure that will not go away
     * by waiting (no privilege, no extension available) is reported once and not
     * retried.
     *
     * @return whether the indexes are ready
     */
    boolean buildWithRetries(long... retryDelaysMillis) {
        for (int attempt = 0;; attempt++) {
            try {
                build();
                return ready;
            } catch (SQLException e) {
                boolean transientFailure = isTransient(e);
                if (!transientFailure || attempt >= retryDelaysMillis.length) {
                    LOGGER.warnf("Substring search on %s stays unindexed: could not create the pg_trgm extension or its "
                            + "indexes (%s). Searching still works, unindexed; %s", collectionName, e.getMessage(),
                            transientFailure
                                    ? "the next restart tries again."
                                    : "grant the role CREATE on the database, or set "
                                            + "eddi.datastore.postgres.substring-search-index=false to stop trying.");
                    return false;
                }
                LOGGER.infof("Substring-search index build for %s failed transiently (%s, SQLState %s); retrying in %d s",
                        collectionName, e.getMessage(), e.getSQLState(), retryDelaysMillis[attempt] / 1000);
                if (!sleep(retryDelaysMillis[attempt])) {
                    return false;
                }
            }
        }
    }

    /**
     * Deadlock and serialization failures (class 40), lock not available (55P03)
     * and a cancelled statement (57014) can succeed on a later attempt; anything
     * else — above all insufficient privilege (42501) — will not.
     */
    static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("40") || "55P03".equals(state) || "57014".equals(state));
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * One attempt: creates the extension and the indexes, repairing any left
     * INVALID, under an advisory lock. Visible for tests, which call it
     * synchronously.
     */
    void build() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY refuses to run in a transaction
            if (!tryLock(conn)) {
                LOGGER.debugf("Another instance is building the substring-search indexes of %s", collectionName);
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
                for (int i = 0; i < fields.size(); i++) {
                    String name = indexNames.get(i);
                    if (Boolean.FALSE.equals(isValid(conn, name))) {
                        LOGGER.infof("Rebuilding substring-search index %s, left invalid by an interrupted build", name);
                        stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
                    }
                    stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON resources USING gin ((data ->> '" + fields.get(i)
                            + "') gin_trgm_ops) WHERE collection_name = '" + collectionName + "'");
                }
            } finally {
                unlock(conn);
            }
            ready = allValid(conn);
            lastCheckMillis = System.currentTimeMillis();
            if (ready) {
                LOGGER.infof("Substring-search indexes ready for %s (%s)", collectionName, String.join(", ", fields));
            }
        }
    }

    /**
     * Whether every index exists and is valid — so a search may be planned per
     * execution and use them. A "no" is re-checked against the catalogue at most
     * once a minute, so an instance that did not build the indexes itself still
     * notices when another one has.
     */
    boolean isReady() {
        if (ready) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckMillis < RECHECK_INTERVAL_MILLIS) {
            return false;
        }
        lastCheckMillis = now;
        try (Connection conn = dataSource.getConnection()) {
            ready = allValid(conn);
        } catch (SQLException e) {
            LOGGER.debugf("Could not check the substring-search indexes of %s: %s", collectionName, e.getMessage());
        }
        return ready;
    }

    List<String> indexNames() {
        return indexNames;
    }

    private boolean allValid(Connection conn) throws SQLException {
        for (String name : indexNames) {
            if (!Boolean.TRUE.equals(isValid(conn, name))) {
                return false;
            }
        }
        return true;
    }

    /** {@code null} when the index does not exist. */
    private static Boolean isValid(Connection conn, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private static boolean tryLock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, BUILD_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * A session lock outlives the pooled connection's checkout, so it is released
     * explicitly.
     */
    private static void unlock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, BUILD_LOCK_KEY);
            ps.execute();
        } catch (SQLException e) {
            LOGGER.warnf("Could not release the substring-search build lock: %s", e.getMessage());
        }
    }

    /**
     * {@code idx_resources_trgm_<collection>_<field>}, lower-cased with a digest of
     * the exact name when that differs, and kept within PostgreSQL's 63-byte limit
     * with the digest at the end (PostgreSQL would truncate silently, cutting the
     * digest off).
     */
    static String indexName(String collectionName, String field) {
        String exact = collectionName.replace('.', '_') + "_" + field;
        String lower = exact.toLowerCase(Locale.ROOT);
        String name = "idx_resources_trgm_" + lower;
        if (lower.equals(exact) && name.length() <= 63) {
            return name;
        }
        String digest = String.format("%08x", exact.hashCode());
        int room = 63 - "idx_resources_trgm_".length() - 1 - digest.length();
        return "idx_resources_trgm_" + lower.substring(0, Math.min(lower.length(), room)) + "_" + digest;
    }
}
