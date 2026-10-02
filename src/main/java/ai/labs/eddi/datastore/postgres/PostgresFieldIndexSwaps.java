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

/**
 * Replaces the ascending field indexes of earlier releases
 * ({@code idx_resources_field_*}) with their {@code DESC NULLS LAST} successors
 * ({@code idx_resources_fdesc_*}) on an existing database, without blocking
 * writes; see {@code PostgresResourceStorage#createFieldIndex}.
 *
 * <h3>Why not at boot</h3> The field indexes are expression indexes on the
 * shared {@code resources} table, which holds a descriptor for every
 * conversation. A plain {@code CREATE INDEX} takes a SHARE lock on that table
 * for the whole build — and the descriptor collection alone hints nine fields —
 * so the first boot of an upgrade stopped every write to every collection, on
 * every instance of the cluster, until all of them were built. Here each
 * replacement is built with {@code CREATE INDEX CONCURRENTLY} on a background
 * thread once boot has settled, as {@link PostgresSubstringSearchIndexes}
 * builds the trigram indexes. Until it is valid, the old index keeps serving
 * equality filters; only the sort of a listing page is unindexed meanwhile.
 *
 * <h3>Order, concurrency and repair</h3> The old index is dropped only once its
 * replacement exists <em>and is valid</em>, so a refused or interrupted build
 * never leaves a field with neither. Each replacement is guarded by its own
 * advisory lock, so two instances booting together never build the same index;
 * the one that does not get the lock tries again later and then finds the work
 * done. An interrupted concurrent build leaves an INVALID index, which
 * {@code CREATE INDEX IF NOT EXISTS} would skip for ever while it still costs
 * every write — it is dropped and rebuilt.
 *
 * <h3>Failure is not fatal</h3> A transient failure (deadlock, lock timeout,
 * cancelled statement, the lock held elsewhere) is retried; one that waiting
 * cannot fix (no privilege) is logged once, and the old index stays.
 */
final class PostgresFieldIndexSwaps {

    private static final Logger LOGGER = Logger.getLogger(PostgresFieldIndexSwaps.class);

    /**
     * First half of each swap's advisory lock key ("eddi"); the second is the hash
     * of the index name.
     */
    private static final int LOCK_CLASS = 0x65646469;

    /**
     * One replacement: build {@code name} {@code ON} {@code target}, then drop
     * {@code legacyName}. All three are built by {@code PostgresResourceStorage}
     * from a sanitised field name.
     */
    record Swap(String name, String target, String legacyName) {
    }

    /** Thrown when another instance holds a swap's lock: worth a retry. */
    static final class LockedElsewhere extends SQLException {
        LockedElsewhere(String indexName) {
            super("another instance is building " + indexName, "55P03");
        }
    }

    /**
     * One concurrent build at a time per JVM: every storage has its own swaps, and
     * two concurrent builds of expression indexes on one table wait for each other
     * and can be aborted as a deadlock.
     */
    private static final Object BUILD_MONITOR = new Object();

    private final DataSource dataSource;
    private final List<Swap> pending;

    PostgresFieldIndexSwaps(DataSource dataSource, List<Swap> swaps) {
        this.dataSource = dataSource;
        this.pending = new ArrayList<>(swaps);
    }

    /** The swaps not done yet. */
    synchronized List<Swap> pending() {
        return List.copyOf(pending);
    }

    /**
     * Runs the swaps on a background thread, once boot has settled; returns at
     * once.
     */
    void runInBackground(long startDelayMillis) {
        Thread.ofVirtual().name("pg-field-index-swap").start(() -> {
            if (sleep(startDelayMillis)) {
                runWithRetries(PostgresSubstringSearchIndexes.RETRY_DELAYS_MILLIS);
            }
        });
    }

    /**
     * Runs, and retries a transient failure after each of
     * {@code retryDelaysMillis}.
     *
     * @return whether every swap is done
     */
    boolean runWithRetries(long... retryDelaysMillis) {
        for (int attempt = 0;; attempt++) {
            try {
                run();
                return true;
            } catch (SQLException e) {
                boolean transientFailure = PostgresSubstringSearchIndexes.isTransient(e);
                if (!transientFailure || attempt >= retryDelaysMillis.length) {
                    LOGGER.warnf("Could not replace the ascending field indexes %s (%s); they keep serving filters, but sorted "
                            + "listings are not read off an index. %s", legacyNames(), e.getMessage(),
                            transientFailure ? "The next restart tries again." : "Grant the role CREATE on the database and restart.");
                    return false;
                }
                LOGGER.infof("Field index replacement failed transiently (%s, SQLState %s); retrying in %d s", e.getMessage(),
                        e.getSQLState(), retryDelaysMillis[attempt] / 1000);
                if (!sleep(retryDelaysMillis[attempt])) {
                    return false;
                }
            }
        }
    }

    /**
     * One attempt over every pending swap. Visible for tests, which call it
     * synchronously.
     */
    void run() throws SQLException {
        for (Swap swap : pending()) {
            synchronized (BUILD_MONITOR) {
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY refuses to run in a transaction
                    swap(conn, swap);
                }
            }
            synchronized (this) {
                pending.remove(swap);
            }
        }
    }

    private static void swap(Connection conn, Swap swap) throws SQLException {
        int lockKey = swap.name().hashCode();
        if (!tryLock(conn, lockKey)) {
            throw new LockedElsewhere(swap.name());
        }
        try (Statement stmt = conn.createStatement()) {
            if (Boolean.FALSE.equals(isValid(conn, swap.name()))) {
                LOGGER.infof("Rebuilding field index %s, left invalid by an interrupted build", swap.name());
                stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + swap.name());
            }
            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + swap.name() + " ON " + swap.target());
            if (!Boolean.TRUE.equals(isValid(conn, swap.name()))) {
                // Never drop the old index without a usable replacement.
                throw new SQLException("index " + swap.name() + " is not valid after its build", "55000");
            }
            stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + swap.legacyName());
            LOGGER.infof("Replaced field index %s with %s", swap.legacyName(), swap.name());
        } finally {
            unlock(conn, lockKey);
        }
    }

    private List<String> legacyNames() {
        return pending().stream().map(Swap::legacyName).toList();
    }

    /**
     * {@code null} when the index does not exist. Resolved through the search path,
     * like the unqualified DDL, so another schema's index of the same name does not
     * answer.
     */
    static Boolean isValid(Connection conn, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT i.indisvalid FROM pg_index i WHERE i.indexrelid = to_regclass(?)")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private static boolean tryLock(Connection conn, int key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            ps.setInt(1, LOCK_CLASS);
            ps.setInt(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * A session lock outlives the pooled connection's checkout, so it is released
     * explicitly.
     */
    private static void unlock(Connection conn, int key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            ps.setInt(1, LOCK_CLASS);
            ps.setInt(2, key);
            ps.execute();
        } catch (SQLException e) {
            LOGGER.warnf("Could not release the field index lock: %s", e.getMessage());
        }
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
}
