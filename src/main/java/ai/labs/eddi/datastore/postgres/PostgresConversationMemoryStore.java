/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import io.quarkus.arc.DefaultBean;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ai.labs.eddi.engine.model.Context.ContextType.valueOf;
import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;

/**
 * PostgreSQL implementation of {@link IConversationMemoryStore}.
 * <p>
 * Stores conversation snapshots as JSONB in a dedicated
 * {@code conversation_memories} table with extracted indexed columns for
 * efficient querying.
 */
@ApplicationScoped
@DefaultBean
public class PostgresConversationMemoryStore implements IConversationMemoryStore, IResourceStore<ConversationMemorySnapshot> {

    private static final Logger LOGGER = Logger.getLogger(PostgresConversationMemoryStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS conversation_memories (
                id UUID PRIMARY KEY,
                AGENT_ID TEXT NOT NULL,
                AGENT_VERSION INTEGER NOT NULL,
                conversation_state TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                data JSONB NOT NULL
            )
            """;

    /**
     * How many times an append re-applies itself on a newer revision before giving
     * up and reporting the conflict. Mirrors the MongoDB store's bound.
     */
    private static final int MAX_APPEND_ATTEMPTS = 5;

    private static final String CREATE_INDEX_STATE = "CREATE INDEX IF NOT EXISTS idx_conv_state ON conversation_memories (conversation_state)";
    private static final String CREATE_INDEX_AGENT = "CREATE INDEX IF NOT EXISTS idx_conv_agent ON conversation_memories (AGENT_ID, AGENT_VERSION)";

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresConversationMemoryStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX_STATE);
            stmt.execute(CREATE_INDEX_AGENT);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize conversation_memories table", e);
        }
    }

    @Override
    public String storeConversationMemorySnapshot(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try {
            String conversationId = snapshot.getConversationId();

            if (conversationId != null) {
                if (isPureAppend(snapshot)) {
                    appendConversationSteps(snapshot);
                    return conversationId;
                }
                long expectedRevision = snapshot.getRevision();
                // The revision the write CREATES, stamped before serializing: the row
                // stores the snapshot verbatim, so the persisted _rev must be the new one
                // or the guard below would keep matching the same revision forever.
                long loadedHistoryRevision = snapshot.getHistoryRevision();
                snapshot.setRevision(expectedRevision + 1);
                // A full-row write may rewrite the history, so it records itself as the
                // latest rewrite; an append in flight elsewhere reads this before retrying.
                snapshot.setHistoryRevision(expectedRevision + 1);
                // Serialized HERE and nowhere earlier: the append path above returns
                // without a full-document body, and this is the whole point of the
                // append path. Serializing up front cost every call one full
                // serialization of the entire conversation that nothing then used.
                String json = jsonSerialization.serialize(snapshot);
                // Update existing, guarded on the revision this write was derived from.
                // COALESCE because a row written before _rev existed carries no such key
                // and must still be writable (it upgrades in the process).
                String sql = """
                        UPDATE conversation_memories
                        SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?, data = ?::jsonb
                        WHERE id = ?::uuid AND COALESCE((data->>'_rev')::bigint, 0) = ?
                        """;
                try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, snapshot.getAgentId());
                    ps.setInt(2, snapshot.getAgentVersion());
                    ps.setString(3, snapshot.getConversationState().name());
                    ps.setString(4, json);
                    ps.setString(5, conversationId);
                    ps.setLong(6, expectedRevision);
                    // No upsert on purpose: zero affected rows means either the row was
                    // deleted while the turn was running (GDPR erasure, retention sweep) or
                    // another writer committed first. Discarding the count dropped the
                    // turn's memory silently and still returned a normal response to the
                    // caller — surface the conflict instead, exactly as the MongoDB store
                    // does.
                    if (ps.executeUpdate() == 0) {
                        // Leave the caller's snapshot describing the revision it was actually
                        // derived from, so a retry re-presents that one.
                        snapshot.setRevision(expectedRevision);
                        snapshot.setHistoryRevision(loadedHistoryRevision);
                        throw conversationNotWritten(conn, conversationId, expectedRevision);
                    }
                }
                snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
            } else {
                // Insert new
                conversationId = UUID.randomUUID().toString();
                snapshot.setId(conversationId);
                // A fresh conversation starts at revision 1, so a legacy-shaped row (no
                // _rev, read as UNVERSIONED_REVISION) can never be mistaken for one.
                snapshot.setRevision(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1);
                snapshot.setHistoryRevision(ConversationMemorySnapshot.UNVERSIONED_REVISION + 1);
                snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
                String json2 = jsonSerialization.serialize(snapshot); // re-serialize with ID and revision
                String sql = """
                        INSERT INTO conversation_memories (id, AGENT_ID, AGENT_VERSION, conversation_state, data)
                        VALUES (?::uuid, ?, ?, ?, ?::jsonb)
                        """;
                try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, conversationId);
                    ps.setString(2, snapshot.getAgentId());
                    ps.setInt(3, snapshot.getAgentVersion());
                    ps.setString(4, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                    ps.setString(5, json2);
                    ps.executeUpdate();
                }
            }
            return conversationId;
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to store conversation memory", e);
        }
    }

    @Override
    public boolean storeConversationMemorySnapshotIfState(ConversationMemorySnapshot snapshot, ConversationState expectedState)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String conversationId = snapshot.getConversationId();
        if (conversationId == null || expectedState == null) {
            // A conditional store only makes sense against an existing row with a known
            // expected state. expectedState can be null when the caller derives it from
            // a live lookup (say-path preTurnPersistedState, undo/redo loaded state) and
            // the row was deleted concurrently — treat as a CAS miss, not an NPE at
            // expectedState.name().
            return false;
        }
        try {
            long expectedRevision = snapshot.getRevision();
            long loadedHistoryRevision = snapshot.getHistoryRevision();
            snapshot.setRevision(expectedRevision + 1);
            snapshot.setHistoryRevision(expectedRevision + 1);
            String json = jsonSerialization.serialize(snapshot);
            // Atomic compare-and-store on BOTH arbiters: the state column (see
            // compareAndSetState), so a concurrent terminal writer that moved the row off
            // expectedState is not overwritten; and the revision, so a concurrent
            // NON-terminal writer — a say turn that appended a step while an undo was in
            // flight — is not overwritten either, which the state filter alone cannot see
            // because both writers leave the same state behind.
            String sql = """
                    UPDATE conversation_memories
                    SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?, data = ?::jsonb
                    WHERE id = ?::uuid AND conversation_state = ? AND COALESCE((data->>'_rev')::bigint, 0) = ?
                    """;
            try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, snapshot.getAgentId());
                ps.setInt(2, snapshot.getAgentVersion());
                ps.setString(3, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                ps.setString(4, json);
                ps.setString(5, conversationId);
                ps.setString(6, expectedState.name());
                ps.setLong(7, expectedRevision);
                if (ps.executeUpdate() > 0) {
                    snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
                    return true;
                }
                snapshot.setRevision(expectedRevision);
                snapshot.setHistoryRevision(loadedHistoryRevision);
                return false;
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to conditionally store conversation memory", e);
        }
    }

    /**
     * Whether this write only <em>adds</em> steps to the row it was derived from,
     * which is what every ordinary turn does. Same three conditions as the MongoDB
     * store — see {@code ConversationMemoryStore.isPureAppend} for why each one
     * matters and what makes leaving the prefix untouched safe.
     */
    private static boolean isPureAppend(ConversationMemorySnapshot snapshot) {
        int baseline = snapshot.getPersistedStepCount();
        int steps = snapshot.getConversationSteps().size();
        int outputs = snapshot.getConversationOutputs().size();
        return baseline >= 0 && steps == outputs && steps > baseline;
    }

    /**
     * Persists a pure-append turn by concatenating only the new steps and outputs
     * onto the stored arrays, server-side.
     * <p>
     * PostgreSQL rewrites the row either way (MVCC), so unlike MongoDB this is not
     * about write amplification — it is about the merge. Re-applying a
     * concatenation on top of whatever a concurrent writer committed keeps both
     * turns; re-applying a whole-row replace would be exactly the overwrite the
     * revision guard exists to refuse.
     * <p>
     * {@code (bodyWithoutArrays) || jsonb_build_object(arrays…)} is shallow object
     * merge with the right side winning, so the result carries exactly the new
     * document's keys plus the concatenated arrays — nothing the snapshot omitted
     * survives. That is what makes this equivalent to the full replace without a
     * field list, and why Postgres needs no counterpart to the MongoDB store's
     * {@code $unset} of the unemitted keys.
     */
    private void appendConversationSteps(ConversationMemorySnapshot snapshot) throws IResourceStore.ResourceStoreException {
        String conversationId = snapshot.getConversationId();
        int baseline = snapshot.getPersistedStepCount();
        int totalSteps = snapshot.getConversationSteps().size();
        // The revision this turn was LOADED at — the retry precondition compares
        // against
        // it and a conflict report names it, so it must not move with the attempts.
        final long loadedRevision = snapshot.getRevision();

        String newStepsJson;
        String newOutputsJson;
        String bodyJson;
        var steps = snapshot.getConversationSteps();
        var outputs = snapshot.getConversationOutputs();
        try {
            newStepsJson = jsonSerialization.serialize(List.copyOf(steps.subList(baseline, totalSteps)));
            newOutputsJson = jsonSerialization.serialize(List.copyOf(outputs.subList(baseline, outputs.size())));
            // The body is serialized with EMPTY arrays, so the stored history is neither
            // re-serialized nor shipped; the SQL supplies the arrays by concatenation.
            snapshot.setConversationSteps(new LinkedList<>());
            snapshot.setConversationOutputs(new LinkedList<>());
            bodyJson = jsonSerialization.serialize(snapshot);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to serialize the conversation turn", e);
        } finally {
            snapshot.setConversationSteps(steps);
            snapshot.setConversationOutputs(outputs);
        }

        // `-` drops the body's (empty) arrays before the merge so the concatenation is
        // what
        // lands. `_histRev` is carried over from the stored row: an append does not
        // rewrite
        // the history and must not reset the marker a concurrent append relies on.
        // The last two WHERE clauses are the retry preconditions — see
        // ConversationMemoryStore.appendPreconditions for why each exists.
        String sql = """
                UPDATE conversation_memories
                SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?,
                    data = ((?::jsonb) - 'conversationSteps' - 'conversationOutputs')
                           || jsonb_build_object(
                                '_rev', COALESCE((data->>'_rev')::bigint, 0) + 1,
                                '_histRev', COALESCE((data->>'_histRev')::bigint, 0),
                                'conversationSteps',
                                    COALESCE(data->'conversationSteps', '[]'::jsonb) || ?::jsonb,
                                'conversationOutputs',
                                    COALESCE(data->'conversationOutputs', '[]'::jsonb) || ?::jsonb)
                WHERE id = ?::uuid AND COALESCE((data->>'_rev')::bigint, 0) = ?
                  AND COALESCE((data->>'_histRev')::bigint, 0) <= ?
                  AND conversation_state NOT IN ('ENDED', 'AWAITING_HUMAN', 'IN_PROGRESS')
                """;

        long attemptRevision = loadedRevision;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            for (int attempt = 1;; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, snapshot.getAgentId());
                    ps.setInt(2, snapshot.getAgentVersion());
                    ps.setString(3, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                    ps.setString(4, bodyJson);
                    ps.setString(5, newStepsJson);
                    ps.setString(6, newOutputsJson);
                    ps.setString(7, conversationId);
                    ps.setLong(8, attemptRevision);
                    ps.setLong(9, loadedRevision);
                    if (ps.executeUpdate() > 0) {
                        if (attempt == 1) {
                            snapshot.setRevision(loadedRevision + 1);
                            snapshot.setPersistedStepCount(totalSteps);
                        } else {
                            // Merged on top of another turn's append: the live memory no longer
                            // mirrors the row. Leave it on the loaded revision so any further
                            // write from it is refused instead of erasing the winner's step.
                            snapshot.setRevision(loadedRevision);
                            snapshot.setPersistedStepCount(ConversationMemorySnapshot.UNKNOWN_PERSISTED_STEP_COUNT);
                        }
                        return;
                    }
                }
                StoredMarkers stored = readMarkers(conn, conversationId);
                if (stored == null) {
                    throw new IResourceStore.ResourceStoreException(
                            "Conversation '" + conversationId + "' no longer exists — the turn was NOT persisted. "
                                    + "The conversation row was deleted concurrently (e.g. erasure or retention cleanup).");
                }
                if (stored.historyRevision() > loadedRevision || NON_APPENDABLE_STATES.contains(stored.state())) {
                    // The winner rewrote the history or moved the conversation into a state a
                    // say turn must never overwrite — see the Mongo store. Refuse and report.
                    throw new ConcurrentConversationModificationException(conversationId, loadedRevision);
                }
                if (attempt >= MAX_APPEND_ATTEMPTS) {
                    LOGGER.warnf("Gave up appending the turn of conversation %s after %d attempts (loaded revision %d, now %d)",
                            conversationId, attempt, loadedRevision, stored.revision());
                    throw new ConcurrentConversationModificationException(conversationId, loadedRevision);
                }
                // Every write since the load was an append: re-apply the SAME concatenation.
                attemptRevision = stored.revision();
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to append the conversation turn", e);
        }
    }

    /** States a say turn's append must never be applied over. */
    private static final Set<String> NON_APPENDABLE_STATES = Set.of(ENDED.name(),
            ConversationState.AWAITING_HUMAN.name(), ConversationState.IN_PROGRESS.name());

    private record StoredMarkers(long revision, long historyRevision, String state) {
    }

    /** The row's concurrency markers, or null when the row no longer exists. */
    private StoredMarkers readMarkers(Connection conn, String conversationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE((data->>'_rev')::bigint, 0) AS rev, COALESCE((data->>'_histRev')::bigint, 0) AS hist, "
                        + "conversation_state FROM conversation_memories WHERE id = ?::uuid")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new StoredMarkers(rs.getLong("rev"), rs.getLong("hist"), rs.getString("conversation_state")) : null;
            }
        }
    }

    /**
     * A zero-row write has exactly two causes and they need different answers from
     * the caller, so tell them apart with one extra probe on the id alone: the row
     * is gone (deleted mid-turn — nothing to retry against) or it is there at a
     * different revision (another writer committed first — a retry from a fresh
     * load can still land).
     */
    private IResourceStore.ResourceStoreException conversationNotWritten(Connection conn, String conversationId, long expectedRevision)
            throws SQLException {
        try (PreparedStatement probe = conn.prepareStatement("SELECT 1 FROM conversation_memories WHERE id = ?::uuid")) {
            probe.setString(1, conversationId);
            try (ResultSet rs = probe.executeQuery()) {
                if (rs.next()) {
                    return new ConcurrentConversationModificationException(conversationId, expectedRevision);
                }
            }
        }
        return new IResourceStore.ResourceStoreException(
                "Conversation '" + conversationId + "' no longer exists — the turn was NOT persisted. "
                        + "The conversation row was deleted concurrently (e.g. erasure or retention cleanup).");
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshot(String conversationId) {
        String sql = "SELECT conversation_state, data FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                    fixContextTypes(snapshot);
                    snapshot.setConversationId(conversationId);
                    applyStateColumn(snapshot, rs.getString("conversation_state"));
                    return snapshot;
                }
                return null;
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to load conversation memory", e);
        }
    }

    @Override
    public List<ConversationMemorySnapshot> loadActiveConversationMemorySnapshot(String agentId, Integer agentVersion)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // A null agentVersion means every version. setInt would unbox it into an NPE.
        String sql = agentVersion != null
                ? "SELECT conversation_state, data FROM conversation_memories WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state != ?"
                : "SELECT conversation_state, data FROM conversation_memories WHERE AGENT_ID = ? AND conversation_state != ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, agentId);
            if (agentVersion != null) {
                ps.setInt(index++, agentVersion);
            }
            ps.setString(index, ENDED.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationMemorySnapshot> results = new ArrayList<>();
                while (rs.next()) {
                    ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                    applyStateColumn(snapshot, rs.getString("conversation_state"));
                    results.add(snapshot);
                }
                return results;
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * The indexed {@code conversation_state} column is the single source of truth
     * for the state: CAS transitions ({@link #compareAndSetState}) and
     * {@link #setConversationState} update the column, while the JSONB document
     * still carries the state it had when the full snapshot was last stored.
     * Loading MUST reconcile the two, or a cancelled/timed-out pause keeps
     * reporting AWAITING_HUMAN from the stale document — wedging say() and
     * resurrecting terminated approvals (parity with MongoDB, where the state lives
     * once in the document the codec reads).
     */
    private static void applyStateColumn(ConversationMemorySnapshot snapshot, String stateColumn) {
        if (stateColumn != null) {
            snapshot.setConversationState(ConversationState.valueOf(stateColumn));
        }
    }

    @Override
    public void setConversationState(String conversationId, ConversationState conversationState) {
        ensureSchema();
        // Patch the JSONB copy of the state along with the column so direct
        // document readers can never observe the pre-transition state.
        String sql = "UPDATE conversation_memories SET conversation_state = ?, "
                + "data = jsonb_set(data, '{conversationState}', to_jsonb(?::text)) WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationState.name());
            ps.setString(2, conversationState.name());
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set conversation state", e);
        }
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation memory", e);
        }
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        ensureSchema();
        String sql = "SELECT conversation_state FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return ConversationState.valueOf(rs.getString("conversation_state"));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation state", e);
        }
    }

    @Override
    public Long getActiveConversationCount(String agentId, Integer agentVersion) {
        ensureSchema();
        // Plan §10(a): AWAITING_HUMAN does not count as active (mirrors the Mongo
        // store) — otherwise a forgotten approval blocks undeploy/GC forever.
        String sql = "SELECT COUNT(*) FROM conversation_memories "
                + "WHERE AGENT_ID = ? AND AGENT_VERSION = ? AND conversation_state NOT IN (?, ?)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, agentVersion);
            ps.setString(3, ENDED.toString());
            ps.setString(4, ConversationState.AWAITING_HUMAN.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count active conversations", e);
        }
    }

    @Override
    public List<String> getEndedConversationIds() {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ENDED.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list ended conversations", e);
        }
    }

    @Override
    public boolean compareAndSetState(String conversationId, ConversationState expected, ConversationState target)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Column is the CAS arbiter; the JSONB copy is patched in the same
        // statement so document and column can never diverge on this transition.
        String sql = "UPDATE conversation_memories SET conversation_state = ?, "
                + "data = jsonb_set(data, '{conversationState}', to_jsonb(?::text)) "
                + "WHERE id = ?::uuid AND conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.name());
            ps.setString(2, target.name());
            ps.setString(3, conversationId);
            ps.setString(4, expected.name());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to compare-and-set conversation state", e);
        }
    }

    @Override
    public List<String> findConversationIdsByState(ConversationState state) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE conversation_state = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to find conversations by state", e);
        }
    }

    @Override
    public void clearHitlBookmark(String conversationId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Terminal cleanup must also drop the tool-level HITL fields so no stale
        // hitlPauseType / pending batch lingers on an ended or cancelled document.
        String sql = "UPDATE conversation_memories SET data = data "
                + "- 'hitlPausedWorkflowId' - 'hitlPausedAbsoluteTaskIndex' - 'hitlPausedAt' "
                + "- 'hitlPauseReason' - 'hitlTimeoutPolicy' - 'hitlApprovalTimeout' "
                + "- 'hitlPauseType' - 'hitlPendingToolCalls' "
                + "WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to clear HITL bookmark", e);
        }
    }

    /**
     * Projected columns for pending-approval summaries — never the full document.
     * {@code hitlPendingToolCalls} is projected as JSON text and reduced to tool
     * NAMES ONLY in {@link #readPendingSummaries} — arguments never leave the
     * database for this bulk listing.
     */
    private static final String PENDING_SUMMARY_SELECT = "SELECT id, AGENT_ID, data->>'userId' AS user_id, data->'hitlPausedAt' AS paused_at_json, "
            + "data->>'hitlPauseReason' AS pause_reason, data->>'hitlTimeoutPolicy' AS timeout_policy, "
            + "data->>'hitlApprovalTimeout' AS approval_timeout, data->>'hitlPauseType' AS pause_type, "
            + "data->'hitlPendingToolCalls'->'calls' AS pending_calls_json "
            + "FROM conversation_memories WHERE conversation_state = ?";

    @Override
    public List<PendingApprovalSummary> findPendingApprovalSummaries(int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Single bounded query with JSONB field extraction — this listing is
        // polled and backs the crash-recovery sweep; deserializing full multi-MB
        // documents here violates the interface's projection contract.
        String sql = PENDING_SUMMARY_SELECT + " LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ConversationState.AWAITING_HUMAN.name());
            ps.setInt(2, limit);
            return readPendingSummaries(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list pending approvals", e);
        }
    }

    @Override
    public List<PendingApprovalSummary> findPendingApprovalSummaries(String ownerUserId, int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Owner filter INSIDE the query: the limit applies after the restriction,
        // so a user's inbox is complete even behind a large global backlog.
        String sql = PENDING_SUMMARY_SELECT + " AND data->>'userId' = ? LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ConversationState.AWAITING_HUMAN.name());
            ps.setString(2, ownerUserId);
            ps.setInt(3, limit);
            return readPendingSummaries(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to list pending approvals for owner", e);
        }
    }

    private List<PendingApprovalSummary> readPendingSummaries(PreparedStatement ps)
            throws SQLException {
        List<PendingApprovalSummary> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                var summary = new PendingApprovalSummary(
                        id, rs.getString("AGENT_ID"), rs.getString("user_id"),
                        parseInstantJson(id, rs.getString("paused_at_json")),
                        rs.getString("pause_reason"), rs.getString("timeout_policy"));
                summary.setApprovalTimeout(rs.getString("approval_timeout"));
                // Null for a rule pause stored before the type was kept — see
                // ConversationMemoryStore.collectPendingSummaries.
                String pauseType = rs.getString("pause_type");
                summary.setPauseType(pauseType != null ? pauseType : ConversationPauseException.PauseOrigin.RULE.name());
                summary.setToolNames(parsePendingToolNamesJson(id, rs.getString("pending_calls_json")));
                out.add(summary);
            }
        }
        return out;
    }

    /**
     * Deserializes the raw JSON value of {@code hitlPausedAt} ({@code data->},
     * keeping the JSON representation) through the SAME mapper that serialized the
     * snapshot — correct for both ISO-string and numeric-timestamp configurations.
     */
    private Instant parseInstantJson(String conversationId, String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson)) {
            return null;
        }
        try {
            return jsonSerialization.deserialize(rawJson, Instant.class);
        } catch (Exception e) {
            LOGGER.warnf("Unparseable hitlPausedAt for conversation %s: %s", conversationId, e.getMessage());
            return null;
        }
    }

    /**
     * Reduces the JSON array projected from {@code hitlPendingToolCalls.calls} to
     * tool NAMES ONLY — never deserializes {@code argumentsRaw}/
     * {@code argumentsRedacted} into memory for this bulk listing.
     */
    private List<String> parsePendingToolNamesJson(String conversationId, String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson)) {
            return null;
        }
        try {
            var calls = jsonSerialization.deserialize(rawJson,
                    PendingToolCallBatch.PendingToolCall[].class);
            if (calls == null) {
                return null;
            }
            return Arrays.stream(calls)
                    .map(PendingToolCallBatch.PendingToolCall::getToolName)
                    .toList();
        } catch (Exception e) {
            LOGGER.warnf("Unparseable hitlPendingToolCalls for conversation %s: %s", conversationId, e.getMessage());
            return null;
        }
    }

    // -- IResourceStore<ConversationMemorySnapshot> methods --

    @Override
    public ConversationMemorySnapshot readIncludingDeleted(String id, Integer version) {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public IResourceStore.IResourceId create(ConversationMemorySnapshot content) throws IResourceStore.ResourceStoreException {
        String id = storeConversationMemorySnapshot(content);
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    @Override
    public ConversationMemorySnapshot read(String id, Integer version) {
        return loadConversationMemorySnapshot(id);
    }

    @Override
    public Integer update(String id, Integer version, ConversationMemorySnapshot content) throws IResourceStore.ResourceStoreException {
        storeConversationMemorySnapshot(content);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) {
        deleteConversationMemorySnapshot(id);
    }

    @Override
    public void deleteAllPermanently(String id) {
        deleteConversationMemorySnapshot(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    // === GDPR ===

    @Override
    public List<String> getConversationIdsByUserId(String userId) {
        ensureSchema();
        String sql = "SELECT id FROM conversation_memories WHERE data->>'userId' = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find conversations by userId", e);
        }
    }

    @Override
    public long deleteConversationsByUserId(String userId) {
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE data->>'userId' = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversations by userId", e);
        }
    }

    /**
     * Fix deserialized context types — same logic as the MongoDB implementation.
     * When deserialized from JSON, Context objects may be represented as
     * LinkedHashMap.
     */
    @SuppressWarnings("unchecked")
    private void fixContextTypes(ConversationMemorySnapshot snapshot) {
        for (var conversationStep : snapshot.getConversationSteps()) {
            for (var aWorkflow : conversationStep.getWorkflows()) {
                for (var lifecycleTask : aWorkflow.getLifecycleTasks()) {
                    if (lifecycleTask.getKey().startsWith("context")) {
                        var result = lifecycleTask.getResult();
                        if (result instanceof LinkedHashMap) {
                            var map = (LinkedHashMap<String, Object>) result;
                            var context = new Context(valueOf(map.get("type").toString()), map.get("value"));
                            lifecycleTask.setResult(context);
                        }
                    }
                }
            }
        }
    }
}
