/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationGenerationCandidate;
import ai.labs.eddi.engine.memory.model.GenerationUpgradeOutcome;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
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
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.model.Context.ContextType.valueOf;
import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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

    /**
     * The cluster fencing clause — see {@code ConversationMemoryStore.withFence}.
     * Appended only to the SQL of a write that carries a token, so an unfenced
     * write (single node, degraded mode) runs exactly the statement it always did.
     */
    private static final String FENCE_CLAUSE = " AND COALESCE((data->>'_fence')::bigint, 0) <= ?";

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

    private void ensureSchema() {
        // Lock-free once the table exists: this runs on every load, the hottest read
        // in the engine.
        if (!schemaInitialized) {
            initSchema();
        }
    }

    private synchronized void initSchema() {
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
                Long fenceToken = snapshot.getFenceToken();
                Long loadedFence = snapshot.getFence();
                snapshot.setFence(snapshot.effectiveFence());
                // Serialized HERE and nowhere earlier: the append path above returns
                // without a full-document body, and this is the whole point of the
                // append path. Serializing up front cost every call one full
                // serialization of the entire conversation that nothing then used.
                String json = jsonSerialization.serialize(snapshot);
                // Update existing, guarded on the revision this write was derived from.
                // COALESCE because a row written before _rev existed carries no such key
                // and must still be writable (it upgrades in the process).
                // The last clause refuses to replace a stored ENDED unless this write ends
                // the conversation itself: ending is a narrow state write that does not
                // move _rev, so the revision guard alone would let a turn already in flight
                // resurrect it — see ConversationMemoryStore.fullReplaceFilter.
                String sql = """
                        UPDATE conversation_memories
                        SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?, data = ?::jsonb
                        WHERE id = ?::uuid AND COALESCE((data->>'_rev')::bigint, 0) = ?
                          AND (conversation_state IS DISTINCT FROM 'ENDED' OR ? = 'ENDED')
                        """;
                if (fenceToken != null) {
                    sql = sql.stripTrailing() + FENCE_CLAUSE;
                }
                try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, snapshot.getAgentId());
                    ps.setInt(2, snapshot.getAgentVersion());
                    ps.setString(3, snapshot.getConversationState().name());
                    ps.setString(4, json);
                    ps.setString(5, conversationId);
                    ps.setLong(6, expectedRevision);
                    ps.setString(7, snapshot.getConversationState().name());
                    if (fenceToken != null) {
                        ps.setLong(8, fenceToken);
                    }
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
                        snapshot.setFence(loadedFence);
                        throw conversationNotWritten(conn, conversationId, expectedRevision, fenceToken);
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
            Long fenceToken = snapshot.getFenceToken();
            Long loadedFence = snapshot.getFence();
            snapshot.setRevision(expectedRevision + 1);
            snapshot.setHistoryRevision(expectedRevision + 1);
            snapshot.setFence(snapshot.effectiveFence());
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
            if (fenceToken != null) {
                sql = sql.stripTrailing() + FENCE_CLAUSE;
            }
            try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, snapshot.getAgentId());
                ps.setInt(2, snapshot.getAgentVersion());
                ps.setString(3, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                ps.setString(4, json);
                ps.setString(5, conversationId);
                ps.setString(6, expectedState.name());
                ps.setLong(7, expectedRevision);
                if (fenceToken != null) {
                    ps.setLong(8, fenceToken);
                }
                if (ps.executeUpdate() > 0) {
                    snapshot.setPersistedStepCount(snapshot.getConversationSteps().size());
                    return true;
                }
                snapshot.setRevision(expectedRevision);
                snapshot.setHistoryRevision(loadedHistoryRevision);
                snapshot.setFence(loadedFence);
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
        final Long fenceToken = snapshot.getFenceToken();

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
        // `_fence` is never taken from the body (it carries the value loaded with the
        // turn): an append is re-applied on top of newer revisions, and a stale body
        // value would move the stored fence backwards. A fenced write raises it with
        // GREATEST; an unfenced one keeps whatever is stored (nothing, on a single
        // node).
        String fenceValue = fenceToken != null
                ? "jsonb_build_object('_fence', GREATEST(COALESCE((data->>'_fence')::bigint, 0), ?))"
                : "(CASE WHEN data->'_fence' IS NOT NULL THEN jsonb_build_object('_fence', data->'_fence') ELSE '{}'::jsonb END)";
        String sql = """
                UPDATE conversation_memories
                SET AGENT_ID = ?, AGENT_VERSION = ?, conversation_state = ?,
                    data = ((?::jsonb) - 'conversationSteps' - 'conversationOutputs' - '_fence')
                           || jsonb_build_object(
                                '_rev', COALESCE((data->>'_rev')::bigint, 0) + 1,
                                '_histRev', COALESCE((data->>'_histRev')::bigint, 0),
                                'conversationSteps',
                                    COALESCE(data->'conversationSteps', '[]'::jsonb) || ?::jsonb,
                                'conversationOutputs',
                                    COALESCE(data->'conversationOutputs', '[]'::jsonb) || ?::jsonb)
                           || """ + fenceValue + """
                WHERE id = ?::uuid AND COALESCE((data->>'_rev')::bigint, 0) = ?
                  AND COALESCE((data->>'_histRev')::bigint, 0) <= ?
                  AND conversation_state NOT IN ('ENDED', 'AWAITING_HUMAN', 'IN_PROGRESS')""";
        if (fenceToken != null) {
            sql = sql + FENCE_CLAUSE;
        }

        long attemptRevision = loadedRevision;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            for (int attempt = 1;; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, snapshot.getAgentId());
                    ps.setInt(2, snapshot.getAgentVersion());
                    ps.setString(3, snapshot.getConversationState() != null ? snapshot.getConversationState().name() : "IN_PROGRESS");
                    int i = 4;
                    ps.setString(i++, bodyJson);
                    ps.setString(i++, newStepsJson);
                    ps.setString(i++, newOutputsJson);
                    if (fenceToken != null) {
                        ps.setLong(i++, fenceToken);
                    }
                    ps.setString(i++, conversationId);
                    ps.setLong(i++, attemptRevision);
                    ps.setLong(i++, loadedRevision);
                    if (fenceToken != null) {
                        ps.setLong(i, fenceToken);
                    }
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
                if (fenceToken != null && stored.fence() > fenceToken) {
                    // A newer lease holder has written: a zombie write. Never retried.
                    throw new ConversationFencedException(conversationId, fenceToken, stored.fence());
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

    private record StoredMarkers(long revision, long historyRevision, String state, long fence) {
    }

    /** The row's concurrency markers, or null when the row no longer exists. */
    private StoredMarkers readMarkers(Connection conn, String conversationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE((data->>'_rev')::bigint, 0) AS rev, COALESCE((data->>'_histRev')::bigint, 0) AS hist, "
                        + "COALESCE((data->>'_fence')::bigint, 0) AS fence, "
                        + "conversation_state FROM conversation_memories WHERE id = ?::uuid")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new StoredMarkers(rs.getLong("rev"), rs.getLong("hist"), rs.getString("conversation_state"), rs.getLong("fence"))
                        : null;
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
    private IResourceStore.ResourceStoreException conversationNotWritten(Connection conn, String conversationId, long expectedRevision,
                                                                         Long fenceToken)
            throws SQLException {
        // An unfenced write keeps the probe it always ran; a fenced one also reads the
        // stored fence, to tell "taken over by another node" from a plain conflict.
        String probeSql = fenceToken == null
                ? "SELECT 1 FROM conversation_memories WHERE id = ?::uuid"
                : "SELECT COALESCE((data->>'_fence')::bigint, 0) AS fence FROM conversation_memories WHERE id = ?::uuid";
        try (PreparedStatement probe = conn.prepareStatement(probeSql)) {
            probe.setString(1, conversationId);
            try (ResultSet rs = probe.executeQuery()) {
                if (rs.next()) {
                    if (fenceToken != null) {
                        long storedFence = rs.getLong("fence");
                        if (storedFence > fenceToken) {
                            return new ConversationFencedException(conversationId, fenceToken, storedFence);
                        }
                    }
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
        if (!PostgresIds.isStorableId(conversationId)) {
            // Not a UUID: not a conversation this store can hold — see PostgresIds.
            return null;
        }
        // A read can be the first statement against a fresh database (GET of an
        // unknown conversation before any was created): without the table it failed
        // with "relation does not exist" — a 500 for what is a plain 404.
        return loadSnapshot(conversationId, "SELECT conversation_state, data FROM conversation_memories WHERE id = ?::uuid");
    }

    /**
     * JSONB expression: {@code document} minus the pending batch's resume-only
     * fields (the frozen transcript, the gating assistant message, the running
     * trace and the batch fingerprint) — see
     * {@link IConversationMemoryStore#loadConversationMemorySnapshotForApprover}.
     * Each call's {@code argumentsRaw} sits inside an array, which {@code #-}
     * cannot address with a wildcard; it is small, capped, and nulled by the
     * approver projection, so it is transferred rather than rewritten here.
     */
    private static String withoutResumeOnlyFields(String document) {
        return "(" + document + " #- '{hitlPendingToolCalls,chatTranscriptJson}' #- '{hitlPendingToolCalls,gatingAssistantMessageJson}' "
                + "#- '{hitlPendingToolCalls,traceSoFar}' #- '{hitlPendingToolCalls,fingerprint}')";
    }

    /**
     * jsonpath selecting the {@code result} of every spend entry of the steps under
     * {@code %s}, in document order (step, workflow, entry) — the order
     * {@code ConversationMemoryUtilities#conversationCost} sums them in.
     */
    private static final String SPEND_VALUES_PATH = "'$.%s[*].workflows[*].lifecycleTasks[*] "
            + "? (@.key == \"audit:cost\" || @.key == \"audit:discarded_cost\").result'";

    /**
     * The tail read — see {@link ConversationMemoryTail}. Cuts the two history
     * arrays down to their last element (only when they are arrays, so any other
     * stored shape deserializes exactly as the full load would), drops the redo
     * cache and the resume-only batch fields, and computes the totals in the
     * database.
     */
    private static final String TAIL_SQL = "SELECT conversation_state, "
            + "(" + withoutResumeOnlyFields("(data - 'redoCache')") + " || " + lastElementOnly("conversationSteps")
            + " || " + lastElementOnly("conversationOutputs") + ") AS data, "
            + arrayLength("conversationSteps") + " AS step_count, "
            + arrayLength("conversationOutputs") + " AS output_count, "
            + arrayLength("redoCache") + " AS redo_count, "
            + "jsonb_path_query_array(data, " + SPEND_VALUES_PATH.formatted("conversationSteps") + ") AS live_spend, "
            + "jsonb_path_query_array(data, " + SPEND_VALUES_PATH.formatted("redoCache") + ") AS redo_spend, "
            + "(jsonb_path_exists(data, '$.conversationSteps[*].packages') OR jsonb_path_exists(data, '$.redoCache[*].packages')) AS legacy_steps "
            + "FROM conversation_memories WHERE id = ?::uuid";

    private static String arrayLength(String key) {
        return "(CASE WHEN jsonb_typeof(data->'" + key + "') = 'array' THEN jsonb_array_length(data->'" + key + "') ELSE 0 END)";
    }

    private static String lastElementOnly(String key) {
        return "(CASE WHEN jsonb_typeof(data->'" + key + "') <> 'array' THEN '{}'::jsonb "
                + "WHEN jsonb_array_length(data->'" + key + "') = 0 THEN jsonb_build_object('" + key + "', '[]'::jsonb) "
                + "ELSE jsonb_build_object('" + key + "', jsonb_build_array(data->'" + key + "'->-1)) END)";
    }

    @Override
    public ConversationMemorySnapshot loadConversationMemorySnapshotForApprover(String conversationId) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return null;
        }
        ensureSchema();
        return loadSnapshot(conversationId,
                "SELECT conversation_state, " + withoutResumeOnlyFields("data") + " AS data FROM conversation_memories WHERE id = ?::uuid");
    }

    /**
     * One row: the document without its history plus the totals the current-step
     * projection needs. A document that still stores a step under EDDI 5's
     * {@code packages} key (read by the load's {@code @JsonAlias}, but not by the
     * jsonpath above) is loaded in full instead, so its cost is never
     * under-counted.
     */
    @Override
    public ConversationMemoryTail loadConversationMemoryTail(String conversationId) throws IResourceStore.ResourceStoreException {
        if (!PostgresIds.isStorableId(conversationId)) {
            return null;
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(TAIL_SQL)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                if (rs.getBoolean("legacy_steps")) {
                    var full = loadConversationMemorySnapshot(conversationId);
                    return full == null ? null : ConversationMemoryTail.ofFullSnapshot(full);
                }
                ConversationMemorySnapshot snapshot = jsonSerialization.deserialize(rs.getString("data"), ConversationMemorySnapshot.class);
                fixContextTypes(snapshot);
                snapshot.setConversationId(conversationId);
                applyStateColumn(snapshot, rs.getString("conversation_state"));
                List<?> liveSpend = jsonSerialization.deserialize(rs.getString("live_spend"), List.class);
                List<?> redoSpend = jsonSerialization.deserialize(rs.getString("redo_spend"), List.class);
                return ConversationMemoryTail.ofProjection(snapshot, rs.getInt("step_count"), rs.getInt("output_count"), rs.getInt("redo_count"),
                        liveSpend, redoSpend);
            }
        } catch (IOException | SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to load conversation memory", e);
        }
    }

    private ConversationMemorySnapshot loadSnapshot(String conversationId, String sql) {
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
     * The newest step-data timestamp of a row, in epoch milliseconds, computed by
     * the database from
     * {@code conversationSteps[].workflows[].lifecycleTasks[].timestamp}. Stored
     * timestamps are numeric (epoch millis); an ISO-8601 string — what a mapper
     * configured otherwise would have written — is read too. {@code NULL} when no
     * step data carries a timestamp, which in a comparison is unknown and so never
     * matches: a conversation that cannot be proven idle is never selected.
     */
    private static final String LAST_INTERACTION_MS = "(SELECT max(CASE "
            + "WHEN jsonb_typeof(ts) = 'number' THEN (ts #>> '{}')::numeric "
            + "WHEN jsonb_typeof(ts) = 'string' AND (ts #>> '{}') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T' "
            + "THEN extract(epoch FROM (ts #>> '{}')::timestamptz) * 1000 END) "
            + "FROM jsonb_path_query(data, '$.conversationSteps[*].workflows[*].lifecycleTasks[*].timestamp') AS ts)";

    private static final String ACTIVITY_SELECT = "SELECT id, conversation_state, AGENT_ID, AGENT_VERSION, " + LAST_INTERACTION_MS
            + " AS last_interaction_ms FROM conversation_memories";

    /** How many rows one statement of {@link #endIdleConversations} ends. */
    private static final int END_IDLE_BATCH_SIZE = 500;

    /**
     * The state a projection reports: {@code null} for a value this version does
     * not know (a row written by a newer one) rather than an exception that would
     * abort a whole page — the same mapping as the MongoDB store.
     */
    private static ConversationState stateOrNull(String state) {
        if (state == null) {
            return null;
        }
        try {
            return ConversationState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ConversationActivitySummary toActivitySummary(ResultSet rs) throws SQLException {
        String state = rs.getString("conversation_state");
        BigDecimal lastInteractionMs = rs.getBigDecimal("last_interaction_ms");
        return new ConversationActivitySummary(rs.getString("id"), stateOrNull(state),
                rs.getString("AGENT_ID"), rs.getObject("AGENT_VERSION", Integer.class),
                lastInteractionMs == null ? null : Instant.ofEpochMilli(lastInteractionMs.longValue()));
    }

    @Override
    public List<ConversationActivitySummary> loadOpenConversationActivity(String agentId, Integer agentVersion, String afterConversationId,
                                                                          int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        var sql = new StringBuilder(ACTIVITY_SELECT).append(" WHERE AGENT_ID = ? AND conversation_state <> ?");
        if (agentVersion != null) {
            sql.append(" AND AGENT_VERSION = ?");
        }
        if (afterConversationId != null) {
            sql.append(" AND id > ?::uuid");
        }
        sql.append(" ORDER BY id LIMIT ?");
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setString(index++, agentId);
            ps.setString(index++, ENDED.toString());
            if (agentVersion != null) {
                ps.setInt(index++, agentVersion);
            }
            if (afterConversationId != null) {
                ps.setString(index++, afterConversationId);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationActivitySummary> batch = new ArrayList<>();
                while (rs.next()) {
                    batch.add(toActivitySummary(rs));
                }
                return batch;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ConversationActivitySummary loadConversationActivity(String conversationId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try {
            UUID.fromString(conversationId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(ACTIVITY_SELECT + " WHERE id = ?::uuid")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? toActivitySummary(rs) : null;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * The generation is absent (or JSON null — {@code ->>} reads both as SQL NULL)
     * or lower than the bound parameter.
     */
    private static final String GENERATION_BELOW = "(data->>'compatibilityGeneration' IS NULL OR (data->>'compatibilityGeneration')::int < ?)";

    /**
     * The stored {@code compatibilityGeneration} JSON text as a number, or
     * {@code null} when absent or not a number. The candidate query casts the field
     * in SQL, so a malformed value fails there first; this keeps the Java side from
     * throwing on one regardless, and treats it like a conversation without a
     * generation.
     */
    static Integer parseGeneration(String conversationId, String generation) {
        if (generation == null) {
            return null;
        }
        try {
            return Integer.valueOf(generation.trim());
        } catch (NumberFormatException e) {
            LOGGER.warnf("Conversation %s has a malformed compatibilityGeneration; treating it as none", sanitize(conversationId));
            return null;
        }
    }

    @Override
    public List<ConversationGenerationCandidate> loadGenerationUpgradeCandidates(String agentId, Collection<Integer> agentVersions,
                                                                                 int belowGeneration, String afterConversationId, int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        boolean versionFilter = agentVersions != null && !agentVersions.isEmpty();
        var sql = new StringBuilder(
                "SELECT id, conversation_state, AGENT_VERSION, data->>'compatibilityGeneration' AS generation FROM conversation_memories "
                        + "WHERE AGENT_ID = ? AND conversation_state <> 'ENDED' AND " + GENERATION_BELOW);
        if (versionFilter) {
            sql.append(" AND AGENT_VERSION IN (").append(String.join(",", Collections.nCopies(agentVersions.size(), "?"))).append(")");
        }
        if (afterConversationId != null) {
            sql.append(" AND id > ?::uuid");
        }
        sql.append(" ORDER BY id LIMIT ?");
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setString(index++, agentId);
            ps.setInt(index++, belowGeneration);
            if (versionFilter) {
                for (Integer version : agentVersions) {
                    ps.setInt(index++, version);
                }
            }
            if (afterConversationId != null) {
                ps.setString(index++, afterConversationId);
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ConversationGenerationCandidate> batch = new ArrayList<>();
                while (rs.next()) {
                    String generation = rs.getString("generation");
                    batch.add(new ConversationGenerationCandidate(rs.getString("id"), stateOrNull(rs.getString("conversation_state")),
                            rs.getObject("AGENT_VERSION", Integer.class), parseGeneration(rs.getString("id"), generation)));
                }
                return batch;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public boolean isValidConversationId(String id) {
        return PostgresIds.isStorableId(id);
    }

    @Override
    public Map<Integer, Long> countEndedGenerationUpgradeCandidates(String agentId, Collection<Integer> agentVersions, int belowGeneration)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        boolean versionFilter = agentVersions != null && !agentVersions.isEmpty();
        var sql = new StringBuilder("SELECT AGENT_VERSION, count(*) AS n FROM conversation_memories "
                + "WHERE AGENT_ID = ? AND conversation_state = 'ENDED' AND " + GENERATION_BELOW);
        if (versionFilter) {
            sql.append(" AND AGENT_VERSION IN (").append(String.join(",", Collections.nCopies(agentVersions.size(), "?"))).append(")");
        }
        sql.append(" GROUP BY AGENT_VERSION");
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setString(index++, agentId);
            ps.setInt(index++, belowGeneration);
            if (versionFilter) {
                for (Integer version : agentVersions) {
                    ps.setInt(index++, version);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<Integer, Long> counts = new HashMap<>();
                while (rs.next()) {
                    counts.put(rs.getInt("AGENT_VERSION"), rs.getLong("n"));
                }
                return counts;
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public GenerationUpgradeOutcome upgradeCompatibilityGeneration(String conversationId, String agentId, int targetGeneration)
            throws IResourceStore.ResourceStoreException {
        if (!PostgresIds.isStorableId(conversationId)) {
            return GenerationUpgradeOutcome.NOT_FOUND;
        }
        ensureSchema();
        String states = GenerationUpgradeOutcome.UPGRADABLE_STATES.stream().map(state -> "'" + state.name() + "'")
                .collect(Collectors.joining(", "));
        // Every reference to `data` on the right-hand side is the OLD row, so the new
        // revision and history revision are both old + 1 — the history revision is what
        // makes an append that loaded the old row refuse (see the interface Javadoc).
        String sql = "UPDATE conversation_memories SET data = jsonb_set(jsonb_set(jsonb_set(data, '{compatibilityGeneration}', to_jsonb(?::int)), "
                + "'{_rev}', to_jsonb(COALESCE((data->>'_rev')::bigint, 0) + 1)), "
                + "'{_histRev}', to_jsonb(COALESCE((data->>'_rev')::bigint, 0) + 1)) "
                + "WHERE id = ?::uuid AND AGENT_ID = ? AND conversation_state IN (" + states + ") AND " + GENERATION_BELOW;
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, targetGeneration);
                ps.setString(2, conversationId);
                ps.setString(3, agentId);
                ps.setInt(4, targetGeneration);
                if (ps.executeUpdate() > 0) {
                    return GenerationUpgradeOutcome.UPGRADED;
                }
            }
            try (PreparedStatement probe = conn
                    .prepareStatement("SELECT AGENT_ID, conversation_state, data->>'compatibilityGeneration' AS generation "
                            + "FROM conversation_memories WHERE id = ?::uuid")) {
                probe.setString(1, conversationId);
                try (ResultSet rs = probe.executeQuery()) {
                    if (!rs.next()) {
                        return GenerationUpgradeOutcome.NOT_FOUND;
                    }
                    String generation = rs.getString("generation");
                    return GenerationUpgradeOutcome.whyNotUpgraded(true, agentId != null && agentId.equals(rs.getString("AGENT_ID")),
                            parseGeneration(conversationId, generation), targetGeneration,
                            stateOrNull(rs.getString("conversation_state")));
                }
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to upgrade the compatibility generation of conversation " + conversationId, e);
        }
    }

    /**
     * Counts, or ends in batches of {@link #END_IDLE_BATCH_SIZE} rows per statement
     * — each statement its own transaction, so ending tens of thousands of large
     * rows never holds one multi-gigabyte transaction (and its WAL) open. The idle
     * predicate is repeated on the outer statement so a row that turns active
     * between the batch's selection and its update is re-checked and left alone.
     */
    @Override
    public long endIdleConversations(Instant idleSince, String agentId, String endReason, boolean dryRun)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String predicate = "conversation_state = 'READY' AND " + (agentId != null ? "AGENT_ID = ? AND " : "") + LAST_INTERACTION_MS + " < ?";
        long cutoffMs = idleSince.toEpochMilli();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            if (dryRun) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM conversation_memories WHERE " + predicate)) {
                    int index = 1;
                    if (agentId != null) {
                        ps.setString(index++, agentId);
                    }
                    ps.setLong(index, cutoffMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            }
            String update = "UPDATE conversation_memories SET conversation_state = 'ENDED', "
                    + "data = jsonb_set(" + (endReason != null ? "jsonb_set(data, '{endReason}', to_jsonb(?::text))" : "data")
                    + ", '{conversationState}', to_jsonb('ENDED'::text)) "
                    + "WHERE id IN (SELECT id FROM conversation_memories WHERE " + predicate + " LIMIT " + END_IDLE_BATCH_SIZE + ") AND "
                    + predicate;
            long ended = 0;
            while (true) {
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    int index = 1;
                    if (endReason != null) {
                        ps.setString(index++, endReason);
                    }
                    for (int pass = 0; pass < 2; pass++) {
                        if (agentId != null) {
                            ps.setString(index++, agentId);
                        }
                        ps.setLong(index++, cutoffMs);
                    }
                    updated = ps.executeUpdate();
                }
                ended += updated;
                if (updated == 0) {
                    return ended;
                }
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to end idle conversations", e);
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
        if (!PostgresIds.isStorableId(conversationId)) {
            return;
        }
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
    public void setConversationEndReason(String conversationId, String endReason) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return;
        }
        ensureSchema();
        String sql = "UPDATE conversation_memories SET data = jsonb_set(data, '{endReason}', to_jsonb(?::text)) WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, endReason);
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set conversation end reason", e);
        }
    }

    @Override
    public void deleteConversationMemorySnapshot(String conversationId) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return;
        }
        ensureSchema();
        String sql = "DELETE FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation memory", e);
        }
    }

    /**
     * One query for the whole batch, reading the indexed columns and three JSONB
     * fields; the steps are counted by the server and never sent.
     */
    @Override
    public Map<String, ConversationListingSummary> loadListingSummaries(Collection<String> conversationIds) {
        ensureSchema();
        Map<String, ConversationListingSummary> summaries = new HashMap<>();
        Map<UUID, String> requested = new HashMap<>();
        for (String conversationId : conversationIds) {
            try {
                if (conversationId != null) {
                    requested.put(UUID.fromString(conversationId), conversationId);
                }
            } catch (IllegalArgumentException e) {
                // not a UUID, so not a conversation this store can hold: no entry
            }
        }
        if (requested.isEmpty()) {
            return summaries;
        }

        String sql = "SELECT id, conversation_state, AGENT_ID, AGENT_VERSION, data->>'userId' AS user_id, "
                + "data->>'environment' AS environment, "
                + "COALESCE(jsonb_array_length(CASE WHEN jsonb_typeof(data->'conversationSteps') = 'array' "
                + "THEN data->'conversationSteps' END), 0) AS step_count "
                + "FROM conversation_memories WHERE id = ANY(?)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("uuid", requested.keySet().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String conversationId = requested.get(rs.getObject("id", UUID.class));
                    String state = rs.getString("conversation_state");
                    String environment = rs.getString("environment");
                    Integer agentVersion = rs.getObject("AGENT_VERSION", Integer.class);
                    summaries.put(conversationId, new ConversationListingSummary(conversationId, rs.getString("user_id"),
                            environment == null ? null : Deployment.Environment.fromString(environment),
                            stateOrNull(state), rs.getString("AGENT_ID"),
                            agentVersion, rs.getInt("step_count")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load conversation listing summaries", e);
        }
        return summaries;
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return null;
        }
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
    public Long getRevision(String conversationId) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return null;
        }
        ensureSchema();
        String sql = "SELECT COALESCE((data->>'_rev')::bigint, 0) AS rev FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("rev") : null;
            }
        } catch (SQLException e) {
            // "Unknown", not "changed": the caller then keeps the memory it has, which is
            // exactly what it did before it asked.
            LOGGER.warnf("Could not read the revision of conversation %s: %s", conversationId, e.getMessage());
            return null;
        }
    }

    @Override
    public StateAndRevision getStateAndRevision(String conversationId) {
        if (!PostgresIds.isStorableId(conversationId)) {
            return new StateAndRevision(null, null);
        }
        ensureSchema();
        String sql = "SELECT conversation_state, COALESCE((data->>'_rev')::bigint, 0) AS rev FROM conversation_memories WHERE id = ?::uuid";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new StateAndRevision(null, null);
                }
                return new StateAndRevision(ConversationState.valueOf(rs.getString("conversation_state")), rs.getLong("rev"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation state and revision", e);
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
    public Map<Integer, Long> getActiveConversationCountsByGeneration(String agentId) {
        ensureSchema();
        // The generation lives in the snapshot JSON; a conversation without one has a
        // NULL there and groups under a null key.
        String sql = "SELECT (data ->> 'compatibilityGeneration')::int, COUNT(*) FROM conversation_memories "
                + "WHERE AGENT_ID = ? AND conversation_state NOT IN (?, ?) GROUP BY 1";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, ENDED.toString());
            ps.setString(3, ConversationState.AWAITING_HUMAN.toString());
            Map<Integer, Long> counts = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getObject(1, Integer.class), rs.getLong(2));
                }
            }
            return counts;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count active conversations by generation", e);
        }
    }

    @Override
    public ConversationUsage getConversationUsage(String agentId) {
        ensureSchema();
        String sql = "SELECT COUNT(*), COUNT(*) FILTER (WHERE conversation_state <> ?), COUNT(DISTINCT data ->> 'userId') "
                + "FROM conversation_memories WHERE AGENT_ID = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ENDED.toString());
            ps.setString(2, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new ConversationUsage(rs.getLong(1), rs.getLong(2), rs.getLong(3));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count conversation usage", e);
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
        if (!PostgresIds.isStorableId(conversationId)) {
            return false;
        }
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
    public void raiseFence(String conversationId, long fence) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // GREATEST: never lowers; `_rev` is deliberately not touched — see
        // IConversationMemoryStore#raiseFence.
        String sql = "UPDATE conversation_memories SET data = jsonb_set(data, '{_fence}', to_jsonb(?::bigint)) "
                + "WHERE id = ?::uuid AND COALESCE((data->>'_fence')::bigint, 0) < ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fence);
            ps.setString(2, conversationId);
            ps.setLong(3, fence);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to raise the fence of conversation " + conversationId, e);
        }
    }

    @Override
    public void clearHitlBookmark(String conversationId) throws IResourceStore.ResourceStoreException {
        if (!PostgresIds.isStorableId(conversationId)) {
            return;
        }
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

    /**
     * Oldest pause first, undated pauses last, so the limit never drops the oldest
     * approval — which the retention sweep and
     * {@code eddi_hitl_pending_oldest_age_seconds} both depend on. The pause time
     * lives only inside {@code data}, as whatever the snapshot mapper wrote: epoch
     * seconds (a JSON number) or an ISO-8601 string. Both are normalised to a
     * timestamp; a string that does not look like a date sorts as undated instead
     * of failing the whole listing on a cast error.
     */
    private static final String PENDING_OLDEST_FIRST = " ORDER BY CASE jsonb_typeof(data->'hitlPausedAt')"
            + " WHEN 'number' THEN to_timestamp((data->>'hitlPausedAt')::double precision)"
            + " WHEN 'string' THEN CASE WHEN data->>'hitlPausedAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(Z|[+-][0-9:]+)$'"
            + " THEN (data->>'hitlPausedAt')::timestamptz END END ASC NULLS LAST";

    @Override
    public List<PendingApprovalSummary> findPendingApprovalSummaries(int limit)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Single bounded query with JSONB field extraction — this listing is
        // polled and backs the crash-recovery sweep; deserializing full multi-MB
        // documents here violates the interface's projection contract.
        String sql = PENDING_SUMMARY_SELECT + PENDING_OLDEST_FIRST + " LIMIT ?";
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
        String sql = PENDING_SUMMARY_SELECT + " AND data->>'userId' = ?" + PENDING_OLDEST_FIRST + " LIMIT ?";
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
                            // Keep the secret flag, as the MongoDB store does: a HITL resume may
                            // re-supply a secret only for a key the paused turn stored as one.
                            if (map.get("secret") instanceof Boolean secret) {
                                context.setSecret(secret);
                            }
                            lifecycleTask.setResult(context);
                        }
                    }
                }
            }
        }
    }
}
