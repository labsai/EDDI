/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.datastore.serialization.Id;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;

/**
 * @author ginccc
 */
public class ConversationMemorySnapshot {
    /**
     * Current document shape this code understands (Wave 0, F6). Bump whenever a
     * Wave adds a field resume-time logic depends on, and register that version's
     * migration in {@code ConversationSchemaMigrations}. Mirrors {@code
     * GroupConversation#CURRENT_SCHEMA_VERSION} for the single-conversation HITL
     * resume path.
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /**
     * The version a stored document claims when its JSON carries no
     * {@code schemaVersion} key. Mirrors
     * {@code GroupConversation#LEGACY_SCHEMA_VERSION} and exists for the same
     * reason: Jackson leaves the field initialiser standing for key-less documents,
     * so the initialiser must be the legacy floor and the creation path
     * ({@code ConversationMemoryUtilities}) stamps {@link #CURRENT_SCHEMA_VERSION}
     * explicitly. While {@code CURRENT} is also {@code 1} this is indistinguishable
     * from initialising to CURRENT — but only by coincidence, and the first bump to
     * {@code 2} would silently re-create the group side's zero-iteration migration
     * bug without this split.
     */
    public static final int LEGACY_SCHEMA_VERSION = 1;
    /**
     * The shape this specific document was last written in. Checked before a
     * resume: newer than {@link #CURRENT_SCHEMA_VERSION} refuses (this deployment
     * predates the document), older runs registered migrations forward. See
     * {@code ConversationSchemaMigrations}.
     */
    private int schemaVersion = LEGACY_SCHEMA_VERSION;
    /**
     * The revision a document that predates the {@code _rev} field reads as. Also
     * the initialiser, so a snapshot built from live memory that was never loaded
     * (a brand-new conversation) claims it too and its first write inserts at
     * revision 1.
     */
    public static final long UNVERSIONED_REVISION = 0L;
    /**
     * Optimistic-concurrency revision of the stored document, persisted as
     * {@code _rev}.
     * <p>
     * On a snapshot that was <em>loaded</em> this is the revision the load saw; on
     * a snapshot about to be <em>written</em> it is therefore the revision the
     * write is derived from, so the stores filter on it and increment it. A
     * zero-match means another writer committed first — see
     * {@code ConcurrentConversationModificationException}.
     * <p>
     * A document written before this field existed deserializes to
     * {@link #UNVERSIONED_REVISION} and upgrades on its next write, with no
     * migration. That is why the stores match "expected
     * {@link #UNVERSIONED_REVISION}" as "{@code _rev} is 0 <em>or</em> absent":
     * MongoDB's {@code {_rev: 0}} does not match a document that has no
     * {@code _rev} at all.
     * <p>
     * The revision guards the document <em>body</em> — the steps, outputs,
     * properties and state that the two snapshot-store methods write. The narrow
     * field-level updates ({@code setConversationState},
     * {@code compareAndSetState}, {@code clearHitlBookmark}) deliberately do NOT
     * bump it: those races are already arbitrated by the conversation-state CAS,
     * and bumping here would convert existing, intentional state handovers (a
     * watchdog parking a turn as EXECUTION_INTERRUPTED while that turn is still
     * completing) into write conflicts.
     */
    private long revision = UNVERSIONED_REVISION;
    /**
     * The revision created by the most recent write that could have REWRITTEN the
     * step history rather than extended it — every full-document write (insert,
     * replace, conditional replace: undo, redo, rerun, a HITL pause or resume
     * commit) stamps it with the revision it creates; an append leaves it alone.
     * Persisted as {@code _histRev}.
     * <p>
     * It is what makes an append's conflict retry safe. A turn that loaded revision
     * {@code r} may re-apply its push on top of a newer document only if every
     * write since {@code r} was itself an append — that is, only while
     * {@code _histRev <= r}. Otherwise the winner removed or reordered steps, or
     * committed a pause, and pushing after it would corrupt the history or erase
     * the pause. A document written before this field existed has none, which reads
     * as {@link #UNVERSIONED_REVISION} and so never blocks a retry by itself.
     * <p>
     * Stamped by the stores; the value carried on a snapshot built from live memory
     * is never trusted by an append (the append does not write this field).
     */
    private long historyRevision = UNVERSIONED_REVISION;
    private String conversationId;
    private String agentId;
    private Integer agentVersion;
    private String userId;
    /**
     * How {@link #userId} came to be, fixed at creation. Absent in documents
     * written before 6.2.0, which deserialize to {@code null} — read as NOT
     * verified, so a legacy conversation must be restarted once before it can
     * resolve a {@code PER_USER} connection. That polarity is the point: the
     * conversations this field exists to distrust are exactly the ones that predate
     * it.
     */
    private ResolutionPrincipal.Provenance resolutionProvenance;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private String hitlPausedWorkflowId;
    private int hitlPausedAbsoluteTaskIndex = -1;
    private Instant hitlPausedAt;
    private String hitlPauseReason;
    private HitlTimeoutPolicy hitlTimeoutPolicy;
    private String hitlApprovalTimeout;
    // Tool-level HITL: null/"RULE" = behavior-rule pause, "TOOL_CALL" = gated tool
    // pause.
    private String hitlPauseType;
    private PendingToolCallBatch hitlPendingToolCalls;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    private Map<String, Property> conversationProperties = new LinkedHashMap<>();
    /**
     * Keys of {@code longTerm} properties whose user-memory write is still owed
     * because the turn that set them never reached its post-conversation tasks
     * (HITL pause, error, cancel). Absent in documents written before 6.2.0, which
     * deserialize to an empty set — the next completed turn simply falls back to
     * the value diff, exactly as before.
     *
     * @see ai.labs.eddi.engine.memory.IConversationMemory#getPendingLongTermWrites()
     */
    private Set<String> pendingLongTermWrites = new LinkedHashSet<>();
    private List<ConversationStepSnapshot> conversationSteps = new LinkedList<>();
    private Stack<ConversationStepSnapshot> redoCache = new Stack<>();
    /**
     * How many steps the stored document held when this conversation was loaded, or
     * {@link #UNKNOWN_PERSISTED_STEP_COUNT} when that is not known.
     * <p>
     * Never persisted — {@code transient} and {@code @JsonIgnore}, and excluded
     * from {@link #TOP_LEVEL_KEYS} for both reasons. It exists so a store can tell
     * the common case ("this turn APPENDED steps to the document it loaded") from
     * everything else ("this turn rewrote the history: an undo, a redo, a rerun, or
     * a load whose steps and outputs had drifted"), and append the new steps
     * instead of rewriting the whole document.
     *
     * @see ai.labs.eddi.engine.memory.IConversationMemory#getPersistedStepCount()
     */
    private transient int persistedStepCount = UNKNOWN_PERSISTED_STEP_COUNT;

    /**
     * "The persisted step count is not known", which forces a full-document write.
     * The safe default in every direction: a snapshot that never came from a load,
     * a document whose steps and outputs disagreed, and a memory whose history was
     * rewritten rather than extended all report it.
     */
    public static final int UNKNOWN_PERSISTED_STEP_COUNT = -1;

    /**
     * Every top-level key a stored conversation document can carry.
     * <p>
     * An append-style write only {@code $set}s the keys the snapshot actually
     * emits, and the serialization omits nulls
     * ({@code SerializationCustomizer.configureObjectMapper} sets
     * {@code JsonInclude.Include.NON_NULL}). Without this set, a field that went
     * from a value to {@code null} during the turn — every HITL bookmark field does
     * exactly that when {@code clearStaleToolPauseState} runs at the start of a
     * fresh turn — would keep its stale value on disk, because {@code $set} merges
     * where {@code replaceOne} replaces. The store {@code $unset}s this set minus
     * the keys the snapshot emitted, which reproduces {@code replaceOne}'s shape
     * exactly.
     * <p>
     * Derived from the declared instance fields rather than maintained by hand, so
     * a new field is covered the moment it is added. The two Jackson renames are
     * mapped explicitly; {@code ConversationMemorySnapshotTopLevelKeysTest} fails
     * if a future {@code @JsonProperty} introduces a third one, because
     * under-inclusion here is what would silently stop a field being cleared.
     * Over-inclusion is harmless: {@code $unset} of an absent key is a no-op.
     */
    public static final Set<String> TOP_LEVEL_KEYS = computeTopLevelKeys();

    private static Set<String> computeTopLevelKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Field field : ConversationMemorySnapshot.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }
            keys.add(switch (field.getName()) {
                case "conversationId" -> "_id";
                case "revision" -> "_rev";
                case "historyRevision" -> "_histRev";
                default -> field.getName();
            });
        }
        return Set.copyOf(keys);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ConversationMemorySnapshot that = (ConversationMemorySnapshot) o;

        return Objects.equals(conversationSteps, that.conversationSteps);
    }

    @Override
    public int hashCode() {
        return conversationSteps != null ? conversationSteps.hashCode() : 0;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    @JsonProperty("_rev")
    public long getRevision() {
        return revision;
    }

    @JsonProperty("_rev")
    public void setRevision(long revision) {
        this.revision = revision;
    }

    @JsonProperty("_histRev")
    public long getHistoryRevision() {
        return historyRevision;
    }

    @JsonProperty("_histRev")
    public void setHistoryRevision(long historyRevision) {
        this.historyRevision = historyRevision;
    }

    @JsonProperty("_id")
    @Id
    public String getId() {
        return conversationId;
    }

    @JsonProperty("_id")
    @Id
    public void setId(String conversationId) {
        this.conversationId = conversationId;
    }

    @JsonIgnore
    public String getConversationId() {
        return conversationId;
    }

    @JsonIgnore
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public static class ConversationStepSnapshot {
        private List<WorkflowRunSnapshot> packages = new LinkedList<>();

        /**
         * The step's rendered output — populated only for redo-cache entries, and
         * {@code null} for the ordinary {@code conversationSteps}, whose outputs are
         * stored once in {@link ConversationMemorySnapshot#conversationOutputs}.
         * <p>
         * Undo/redo in live memory always kept the output, because the step object
         * carries it. Serialisation did not: a redo entry rehydrated as
         * {@code new ConversationStep(new ConversationOutput())}, so
         * {@code redoLastStep()} pushed an <em>empty</em> output over the answer it was
         * supposed to restore. Since every request reloads memory from the store, that
         * always fired in practice — redo returned 200 while destroying the turn, and
         * the model lost it too, because {@code conversationOutputs} is what
         * {@code ConversationHistoryBuilder} reads.
         * <p>
         * Absent in documents written before this field existed; those deserialize to
         * {@code null} and load exactly as they did before.
         */
        private ConversationOutput conversationOutput;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            ConversationStepSnapshot that = (ConversationStepSnapshot) o;

            return Objects.equals(packages, that.packages)
                    && Objects.equals(conversationOutput, that.conversationOutput);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packages, conversationOutput);
        }

        public List<WorkflowRunSnapshot> getWorkflows() {
            return packages;
        }

        public void setWorkflows(List<WorkflowRunSnapshot> packages) {
            this.packages = packages;
        }

        public ConversationOutput getConversationOutput() {
            return conversationOutput;
        }

        public void setConversationOutput(ConversationOutput conversationOutput) {
            this.conversationOutput = conversationOutput;
        }

    }

    public static class WorkflowRunSnapshot {
        private List<ResultSnapshot> lifecycleTasks = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            WorkflowRunSnapshot that = (WorkflowRunSnapshot) o;

            return Objects.equals(lifecycleTasks, that.lifecycleTasks);
        }

        @Override
        public int hashCode() {
            return 31 * (lifecycleTasks != null ? lifecycleTasks.hashCode() : 0);
        }

        public List<ResultSnapshot> getLifecycleTasks() {
            return lifecycleTasks;
        }

        public void setLifecycleTasks(List<ResultSnapshot> lifecycleTasks) {
            this.lifecycleTasks = lifecycleTasks;
        }

    }

    public static class ResultSnapshot {
        private String key;
        private Object result;
        private List<?> possibleResults;
        private Date timestamp;
        private String originWorkflowId;
        private boolean isPublic;
        private boolean committed = true;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            ResultSnapshot that = (ResultSnapshot) o;

            if (Objects.equals(key, that.key)) {
                return Objects.equals(possibleResults, that.possibleResults);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (possibleResults != null ? possibleResults.hashCode() : 0);
            return result;
        }

        public ResultSnapshot() {
        }

        public ResultSnapshot(String key, Object result, List<?> possibleResults, Date timestamp, String originWorkflowId, boolean isPublic) {
            this(key, result, possibleResults, timestamp, originWorkflowId, isPublic, true);
        }

        public ResultSnapshot(String key, Object result, List<?> possibleResults, Date timestamp, String originWorkflowId, boolean isPublic,
                boolean committed) {
            this.key = key;
            this.result = result;
            this.possibleResults = possibleResults;
            this.timestamp = timestamp;
            this.originWorkflowId = originWorkflowId;
            this.isPublic = isPublic;
            this.committed = committed;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public List<?> getPossibleResults() {
            return possibleResults;
        }

        public void setPossibleResults(List<?> possibleResults) {
            this.possibleResults = possibleResults;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }

        public String getOriginWorkflowId() {
            return originWorkflowId;
        }

        public void setOriginWorkflowId(String originWorkflowId) {
            this.originWorkflowId = originWorkflowId;
        }

        public boolean isPublic() {
            return isPublic;
        }

        public void setPublic(boolean isPublic) {
            this.isPublic = isPublic;
        }

        public boolean isCommitted() {
            return committed;
        }

        public void setCommitted(boolean committed) {
            this.committed = committed;
        }

        @Override
        public String toString() {
            return "ResultSnapshot(" + "key=" + key + ", result=" + result + ", possibleResults=" + possibleResults + ", timestamp=" + timestamp
                    + ", originWorkflowId=" + originWorkflowId + ", isPublic=" + isPublic + ", committed=" + committed + ")";
        }
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public ResolutionPrincipal.Provenance getResolutionProvenance() {
        return resolutionProvenance;
    }

    public void setResolutionProvenance(ResolutionPrincipal.Provenance resolutionProvenance) {
        this.resolutionProvenance = resolutionProvenance;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public ConversationState getConversationState() {
        return conversationState;
    }

    public void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
    }

    public String getHitlPausedWorkflowId() {
        return hitlPausedWorkflowId;
    }

    public void setHitlPausedWorkflowId(String hitlPausedWorkflowId) {
        this.hitlPausedWorkflowId = hitlPausedWorkflowId;
    }

    public int getHitlPausedAbsoluteTaskIndex() {
        return hitlPausedAbsoluteTaskIndex;
    }

    public void setHitlPausedAbsoluteTaskIndex(int hitlPausedAbsoluteTaskIndex) {
        this.hitlPausedAbsoluteTaskIndex = hitlPausedAbsoluteTaskIndex;
    }

    public Instant getHitlPausedAt() {
        return hitlPausedAt;
    }

    public void setHitlPausedAt(Instant hitlPausedAt) {
        this.hitlPausedAt = hitlPausedAt;
    }

    public String getHitlPauseReason() {
        return hitlPauseReason;
    }

    public void setHitlPauseReason(String hitlPauseReason) {
        this.hitlPauseReason = hitlPauseReason;
    }

    public HitlTimeoutPolicy getHitlTimeoutPolicy() {
        return hitlTimeoutPolicy;
    }

    public void setHitlTimeoutPolicy(HitlTimeoutPolicy hitlTimeoutPolicy) {
        this.hitlTimeoutPolicy = hitlTimeoutPolicy;
    }

    public String getHitlApprovalTimeout() {
        return hitlApprovalTimeout;
    }

    public void setHitlApprovalTimeout(String hitlApprovalTimeout) {
        this.hitlApprovalTimeout = hitlApprovalTimeout;
    }

    public String getHitlPauseType() {
        return hitlPauseType;
    }

    public void setHitlPauseType(String hitlPauseType) {
        this.hitlPauseType = hitlPauseType;
    }

    public PendingToolCallBatch getHitlPendingToolCalls() {
        return hitlPendingToolCalls;
    }

    public void setHitlPendingToolCalls(PendingToolCallBatch hitlPendingToolCalls) {
        this.hitlPendingToolCalls = hitlPendingToolCalls;
    }

    public List<ConversationOutput> getConversationOutputs() {
        return conversationOutputs;
    }

    public void setConversationOutputs(List<ConversationOutput> conversationOutputs) {
        this.conversationOutputs = conversationOutputs;
    }

    public Map<String, Property> getConversationProperties() {
        return conversationProperties;
    }

    public void setConversationProperties(Map<String, Property> conversationProperties) {
        this.conversationProperties = conversationProperties;
    }

    /**
     * Returns the live set, deliberately — do not "fix" this to return a copy or an
     * unmodifiable view.
     * <p>
     * Static analysis flags this as exposing internal representation, which is true
     * of every accessor on this Jackson DTO. Here the obvious remedy breaks
     * persistence: {@code ConversationMemoryUtilities.convertConversationMemory}
     * populates the field with
     * {@code snapshot.getPendingLongTermWrites().addAll(memory.getPendingLongTermWrites())},
     * so {@code Set.copyOf} would turn that into a <em>silent</em> no-op — the
     * deferred writes would never reach the snapshot, reintroducing exactly the
     * lost-{@code longTerm}-write bug (G6) this field was added to prevent — and
     * {@code Collections.unmodifiableSet} would throw there instead.
     * <p>
     * Callers that must not alias take their own copy at the point it matters; see
     * {@code Conversation.storePropertiesPermanently}.
     */
    public Set<String> getPendingLongTermWrites() {
        return pendingLongTermWrites;
    }

    public void setPendingLongTermWrites(Set<String> pendingLongTermWrites) {
        this.pendingLongTermWrites = pendingLongTermWrites == null ? new LinkedHashSet<>() : pendingLongTermWrites;
    }

    public List<ConversationStepSnapshot> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<ConversationStepSnapshot> conversationSteps) {
        this.conversationSteps = conversationSteps;
    }

    public Stack<ConversationStepSnapshot> getRedoCache() {
        return redoCache;
    }

    public void setRedoCache(Stack<ConversationStepSnapshot> redoCache) {
        this.redoCache = redoCache;
    }

    /**
     * How many steps the document held when this conversation was loaded. See
     * {@link #persistedStepCount}.
     */
    @JsonIgnore
    public int getPersistedStepCount() {
        return persistedStepCount;
    }

    @JsonIgnore
    public void setPersistedStepCount(int persistedStepCount) {
        this.persistedStepCount = persistedStepCount;
    }
}
