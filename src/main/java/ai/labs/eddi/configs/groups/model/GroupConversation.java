/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.memory.model.Attachment;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transcript record for a group conversation. Persisted with a single-version
 * model via {@code AbstractResourceStore} (DB-agnostic).
 *
 * @author ginccc
 */
public class GroupConversation {
    /**
     * Current document shape this code understands (Wave 0, F6). Bump whenever a
     * Wave adds a field resume-time logic depends on, and register that version's
     * migration in {@code GroupConversationSchemaMigrations}.
     * <p>
     * Version 4 — the 2026-08 group-features release shape, carrying BOTH
     * resume-critical additions that ship together: {@code runtimePhases} (I12 — a
     * facilitator-diverged phase list a resume must honor; an older pod would
     * mis-index every bookmark into the config's list) and {@code negotiationState}
     * (I11 — an older pod re-saving a paused document would drop the proposals,
     * signed acceptances and concession ledger). No migration entry for either: v3
     * documents have neither field and Jackson defaults them correctly (identity
     * hop).
     */
    // v4 (this release): the release shape — adds I11's negotiationState, I12's
    // runtimePhases and I6's pausedRepeatSliceBase, all resume-consumed. No
    // migration entries needed: Jackson defaults each on legacy documents to its
    // pre-v4 behavior (null / null / -1).
    public static final int CURRENT_SCHEMA_VERSION = 4;
    /**
     * The version a stored document claims when its JSON carries no
     * {@code schemaVersion} key — i.e. it was written before F6 existed, which is
     * every pre-F6 production document. Jackson runs the no-arg constructor and
     * leaves the field initialiser standing either way, so the initialiser cannot
     * distinguish "absent" from "current": it MUST be this legacy floor, and the
     * single creation point ({@code GroupConversationService}) stamps
     * {@link #CURRENT_SCHEMA_VERSION} explicitly. Initialising the field to CURRENT
     * instead made key-less documents claim the current version and
     * {@code prepareForResume}'s ladder run zero iterations on exactly the
     * documents it exists for.
     */
    public static final int LEGACY_SCHEMA_VERSION = 1;
    /**
     * The shape this specific document was last written in. Checked before a
     * resume: newer than {@link #CURRENT_SCHEMA_VERSION} refuses (this deployment
     * predates the document), older runs registered migrations forward. A pause can
     * sit in storage for days — long enough for a deploy to land in between — so
     * this is the group side's document-shape guard, alongside the existing
     * per-resume config-drift check (bookmarked phase vs. current config) that
     * guards a different axis. See {@code GroupConversationSchemaMigrations}.
     */
    private int schemaVersion = LEGACY_SCHEMA_VERSION;
    private String id;
    private String groupId;
    private String userId;
    private GroupConversationState state;
    private String originalQuestion;
    /**
     * The question driving the CURRENT run's phases. Equals
     * {@link #originalQuestion} for the initial round; a continuation round sets it
     * to the follow-up question so an HITL resume re-runs the remaining phases with
     * the right question. Kept separate from {@code originalQuestion} (which the UI
     * renders as the conversation title) so continuations don't rewrite that title.
     * {@code null} on legacy documents — resume falls back to
     * {@code originalQuestion}.
     */
    private String resumeQuestion;
    private List<TranscriptEntry> transcript = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> memberConversationIds = new ConcurrentHashMap<>();
    /**
     * Maps agentId → displayName for all group members. Populated at discussion
     * start.
     */
    // ConcurrentHashMap for the same reason the three lists above are
    // copy-on-write: Jackson walks this map unguarded on every persist, and
    // RecruitAgentTool now writes it from a member-turn thread. A plain
    // LinkedHashMap here re-opens the ConcurrentModificationException that
    // turns a successful discussion into a FAILED one.
    private Map<String, String> memberDisplayNames = new ConcurrentHashMap<>();
    /**
     * Per-conversation dollar-cost attribution (Wave 0, F5): attribution key → that
     * conversation's cumulative tracked cost. The invariant is <b>one key = one
     * conversation</b> (recording is by replacement, which is only idempotent under
     * that reading), so keys are NOT uniformly agent ids: an ordinary member's key
     * is its agentId, a separate-conversation turn uses its conversation key (I2's
     * {@code __convergence_judge}, I4's {@code __dissent__*}), and a nested GROUP
     * member's children are keyed {@code agentId:childConversationId} — each turn
     * of a GROUP member is a fresh child discussion, and a per-agent key would
     * replace child N−1's whole spend with child N's. Consumers wanting a member's
     * total must sum matching keys; {@link #totalCost} is always the sum of
     * everything. Thread-safe: PARALLEL phases accumulate from multiple member
     * turns concurrently.
     */
    private Map<String, Double> memberCosts = new ConcurrentHashMap<>();
    /**
     * Sum of {@link #memberCosts}, recomputed by {@code GroupCostLedger} on every
     * update.
     */
    // volatile: written under memberCosts' monitor by parallel member turns, but
    // read
    // unsynchronized by enforceCeiling/wouldExceedCeiling on the orchestrator
    // thread.
    // Without it there is no happens-before edge, so a ceiling can silently fail to
    // fire after a PARALLEL batch — and a non-volatile 64-bit read may tear (JLS
    // 17.7).
    private volatile double totalCost;
    private int currentPhaseIndex;
    private String currentPhaseName;
    private String synthesizedAnswer;
    /**
     * Typed outcome of the discussion (Wave 0, F3) — verdict, vote, agreement or
     * award. {@code null} until a decision-producing feature (I3 verdicts, I11
     * agreements, I14 votes, I18 awards) sets it; none of those exist yet, so this
     * is {@code null} for every discussion today. {@link #synthesizedAnswer} is
     * always prose; {@code decision} is the structured form of a conclusion when
     * one of those features ran.
     */
    private DecisionRecord decision;
    /**
     * The negotiation's proposals + concession ledger (I11). {@code null} until the
     * first PROPOSAL/BARGAIN phase produces state; persisted with the document so a
     * pause/resume keeps the table as it stood.
     */
    private NegotiationState negotiation;
    private int depth;
    /** Current discussion round (1-based). Incremented by continueDiscussion(). */
    private int round = 1;

    /**
     * Index into {@link #transcript} where the CURRENT round's entries begin.
     * <p>
     * A continuation re-runs every phase from index 0 against a transcript that
     * still holds the previous round's entries, and a transcript entry carries no
     * round of its own — only a phase index, which repeats each round. So "the
     * latest SYNTHESIS" is ambiguous across rounds unless something marks where
     * this round started. Without it, a round whose synthesis produced nothing
     * (judge undeployed, timed out, abstained, or the cost ceiling fired) silently
     * adopted the PREVIOUS round's conclusion: the verdict, the dissents raised
     * against it, and the answer itself, all reported as this round's, for a
     * question this round never answered.
     * <p>
     * Zero for a first round, which is exactly "the whole transcript".
     */
    private int roundStartTranscriptIndex;

    public int getRoundStartTranscriptIndex() {
        return roundStartTranscriptIndex;
    }

    public void setRoundStartTranscriptIndex(int roundStartTranscriptIndex) {
        this.roundStartTranscriptIndex = roundStartTranscriptIndex;
    }

    /**
     * The phase list the CURRENT round actually runs (I12), persisted only once a
     * facilitator move diverges it from the config's phases — a CALL_VOTE insertion
     * or an EXTEND_PHASE repeat bump. {@code null} means the config is
     * authoritative, which is every discussion without a facilitator intervention.
     * <p>
     * Load-bearing across a pause: every resume path (approval, human turn, crash
     * recovery) must execute and drift-check against THIS list when it is set —
     * resolving the config's phases instead would mis-index the bookmark into a
     * list the pause was not taken against. Schema version 4 guards exactly that
     * (see {@link #CURRENT_SCHEMA_VERSION}). Cleared when a round completes: a
     * facilitator's insertions are one-off by design, so a continuation round
     * starts from the config again.
     */
    private List<AgentGroupConfiguration.DiscussionPhase> runtimePhases;

    /**
     * Executed non-CONTINUE facilitator moves so far (I12) — the counter
     * {@code FacilitatorConfig.maxMovesPerDiscussion} caps. Persisted so a
     * pause/resume cannot refill the budget. Rejected attempts never increment it.
     */
    private int facilitatorMoveCount;

    /**
     * EXTEND_PHASE count per phase index (I12), capped at 2 per phase. Keyed by the
     * phase's index rendered as a string (document maps key by string). Safe under
     * CALL_VOTE insertions: insertions land at {@code currentPhase + 1}, so an
     * index that already carries a count (current or earlier) never shifts, and
     * later phases have no counts yet. Cleared with {@link #runtimePhases} when the
     * round completes.
     */
    private Map<String, Integer> facilitatorExtensions = new ConcurrentHashMap<>();

    public List<AgentGroupConfiguration.DiscussionPhase> getRuntimePhases() {
        return runtimePhases;
    }

    public void setRuntimePhases(List<AgentGroupConfiguration.DiscussionPhase> runtimePhases) {
        this.runtimePhases = runtimePhases;
    }

    public int getFacilitatorMoveCount() {
        return facilitatorMoveCount;
    }

    public void setFacilitatorMoveCount(int facilitatorMoveCount) {
        this.facilitatorMoveCount = facilitatorMoveCount;
    }

    /**
     * Read-only view — mutation goes through {@link #recordFacilitatorExtension}
     * and {@link #clearFacilitatorExtensions}, so the caps cannot be edited behind
     * the conversation's back.
     */
    public Map<String, Integer> getFacilitatorExtensions() {
        return Collections.unmodifiableMap(facilitatorExtensions);
    }

    /** EXTEND_PHASE count for one phase index (I12). */
    public int facilitatorExtensionCount(int phaseIdx) {
        return facilitatorExtensions.getOrDefault(String.valueOf(phaseIdx), 0);
    }

    public void recordFacilitatorExtension(int phaseIdx) {
        facilitatorExtensions.merge(String.valueOf(phaseIdx), 1, Integer::sum);
    }

    public void clearFacilitatorExtensions() {
        facilitatorExtensions.clear();
    }

    public void setFacilitatorExtensions(Map<String, Integer> facilitatorExtensions) {
        this.facilitatorExtensions = facilitatorExtensions != null
                ? new ConcurrentHashMap<>(facilitatorExtensions)
                : new ConcurrentHashMap<>();
    }

    /**
     * Rolling summary of transcript entries {@code [0, summaryUpToIndex)} for
     * FULL-scope windowed rendering (I9), extended incrementally at phase
     * boundaries by {@code GroupContextBuilder.updateWindowSummary}. A derived view
     * only — the transcript itself is never modified. {@code null} until windowing
     * first summarizes; legacy documents deserialize to exactly that state and
     * simply start summarizing at the next boundary, so this is additive and needs
     * no {@code CURRENT_SCHEMA_VERSION} bump.
     */
    private String transcriptSummary;
    /** Exclusive raw-transcript index {@link #transcriptSummary} covers through. */
    private int summaryUpToIndex;
    /**
     * The ANONYMOUS-scope twin of {@link #transcriptSummary}, built from
     * "Anonymous"-labelled input so an ANONYMOUS phase's summary can never leak
     * attribution (no de-anonymization). Kept separately rather than reusing the
     * FULL summary, which contains real speaker names.
     */
    private String anonymousTranscriptSummary;
    /**
     * Exclusive raw-transcript index {@link #anonymousTranscriptSummary} covers
     * through.
     */
    private int anonymousSummaryUpToIndex;

    public String getTranscriptSummary() {
        return transcriptSummary;
    }

    public void setTranscriptSummary(String transcriptSummary) {
        this.transcriptSummary = transcriptSummary;
    }

    public int getSummaryUpToIndex() {
        return summaryUpToIndex;
    }

    public void setSummaryUpToIndex(int summaryUpToIndex) {
        this.summaryUpToIndex = summaryUpToIndex;
    }

    public String getAnonymousTranscriptSummary() {
        return anonymousTranscriptSummary;
    }

    public void setAnonymousTranscriptSummary(String anonymousTranscriptSummary) {
        this.anonymousTranscriptSummary = anonymousTranscriptSummary;
    }

    public int getAnonymousSummaryUpToIndex() {
        return anonymousSummaryUpToIndex;
    }

    public void setAnonymousSummaryUpToIndex(int anonymousSummaryUpToIndex) {
        this.anonymousSummaryUpToIndex = anonymousSummaryUpToIndex;
    }
    private SharedTaskList taskList;
    /** Agents dynamically added during the discussion (recruited or created). */
    private List<AgentGroupConfiguration.GroupMember> dynamicMembers = new CopyOnWriteArrayList<>();
    /** Agent IDs created during this discussion (for lifecycle cleanup). */
    // CopyOnWriteArrayList, not synchronizedList: these three are iterated without
    // a monitor by Jackson every time the loop persists the whole document, and by
    // cleanup on teardown, while member turns and the recruit/task tools append to
    // them. synchronizedList makes each add atomic but does NOT make unguarded
    // iteration safe — a concurrent add throws ConcurrentModificationException out
    // of conversationStore.update, which executeDiscussion's catch-all converts
    // into a FAILED discussion. All three are small (agent ids, roster additions)
    // and append-mostly, so copy-on-write's per-write copy is the right trade;
    // the transcript deliberately stays synchronizedList because it grows large
    // enough that copying on every entry would be quadratic.
    private List<String> createdAgentIds = new CopyOnWriteArrayList<>();

    /**
     * Agents recruited into this discussion at runtime (I7).
     * <p>
     * Deliberately NOT merged into {@link #createdAgentIds}: that list drives
     * {@code cleanupEphemeralAgents}, which undeploys what the discussion created.
     * A recruit is a pre-existing agent this discussion borrowed, so undeploying it
     * would take it away from every other conversation using it. Two lists because
     * they mean two different things at teardown.
     */
    private List<String> recruitedAgentIds = new CopyOnWriteArrayList<>();
    /** Agent IDs explicitly retained by the creating agent (skip cleanup). */
    private Set<String> retainedAgentIds = ConcurrentHashMap.newKeySet();
    /**
     * Agent IDs torn down during this discussion — a tombstone, not a log.
     * <p>
     * {@code propagateDynamicAgentTracking} folds each member turn's
     * {@code dynamic:created_agent_ids} snapshot into {@link #createdAgentIds}, and
     * those snapshots are per-member and can arrive stale: member B's turn can
     * carry a created list that still names an agent member A tore down between the
     * two. Without a tombstone the id is simply re-added, so it keeps occupying a
     * {@code maxCreatedAgentsPerDiscussion} slot and terminal cleanup keeps
     * retrying its deletion. This set is consulted on every merge, so a teardown is
     * final regardless of which order the snapshots land in.
     */
    private Set<String> tornDownAgentIds = ConcurrentHashMap.newKeySet();
    private int pausedAtPhaseIndex = -1;
    private int pausedTurnCount = 0;
    /**
     * Transcript index where the PAUSED repeat's entries begin (I6), or {@code -1}.
     * A human turn pauses MID-repeat, after other speakers already appended this
     * repeat's entries — the resumed leg recomputing "size at top of repeat" would
     * slice only what came AFTER the pause, so the convergence check (and every
     * later consumer of the repeat slice) silently loses the pre-pause
     * contributions. Persisted with the pause, consumed exactly once with the
     * speaker bookmark. {@code -1} on legacy documents keeps the old recompute.
     */
    private int pausedRepeatSliceBase = -1;

    public int getPausedRepeatSliceBase() {
        return pausedRepeatSliceBase;
    }

    public void setPausedRepeatSliceBase(int pausedRepeatSliceBase) {
        this.pausedRepeatSliceBase = pausedRepeatSliceBase;
    }
    private String pausedPhaseName;
    private Instant pausedAt;
    private HitlPauseType hitlPauseType;
    /** Human-readable reason for the pause (from HITL gate). */
    private String hitlPauseReason;
    /**
     * Where inside a SEQUENTIAL phase's speaker list a pause landed (Wave 0, F2).
     * {@code null} for {@code PHASE} and {@code TASK} pauses, which land at a
     * phase/task boundary, never mid-speaker-list. I6's HUMAN_TURN pauses are the
     * producer: they pause ON a specific speaker within a running phase; see
     * {@link ResumePoint}'s own Javadoc (and its {@code HUMAN_TURN_PARALLEL}
     * carve-out) for the resume semantics.
     */
    private ResumePoint resumePoint;
    /**
     * The human member's turn an {@code AWAITING_HUMAN_INPUT} pause is waiting on
     * (I6). Non-null exactly while the state is AWAITING_HUMAN_INPUT.
     */
    private PendingHumanInput pendingHumanInput;
    /** Timeout policy copied from config at pause time (Phase 6d). */
    private HitlTimeoutPolicy hitlTimeoutPolicy;
    /**
     * Approval timeout duration (ISO-8601) copied from config at pause time (Phase
     * 6d).
     */
    private String hitlApprovalTimeout;
    /**
     * Fingerprint of the task state at the previous TASK-granularity pause (#4). A
     * resume that re-pauses at the SAME phase with an identical fingerprint made no
     * progress — the discussion is failed instead of re-pausing, guaranteeing
     * termination of the pause→approve→pause loop. Null until the first TASK pause;
     * cleared on successful completion.
     */
    private String hitlLastPauseFingerprint;
    private Instant created;
    private Instant lastModified;

    /**
     * Transient reference to the group's dynamic agent configuration. Set by
     * {@code GroupConversationService.executeDiscussion()} at the start of a
     * discussion so that {@code executeAgentTurn()} can pass it to member agents
     * via context. Never persisted to MongoDB or serialized to REST.
     */
    @JsonIgnore
    private transient AgentGroupConfiguration.DynamicAgentConfig dynamicAgentConfig;

    /**
     * Transient attachments for this discussion. Set at fan-out by
     * {@code GroupConversationService} — inline files are materialized into the
     * blob store (owned by this group conversation) and each member conversation is
     * granted access. Not persisted to the transcript document; the blobs live in
     * {@code IAttachmentStore} bound to this conversation's id.
     */
    @JsonIgnore
    private transient List<Attachment> attachments;

    /**
     * Set by a phase executor (I1) when a turn/wave observes
     * {@code getTotalCost() > protocol.maxCostPerDiscussion()}, naming the policy
     * that fired. Read back by {@code executeDiscussion}'s own phase loop right
     * after the phase-execution call returns, then cleared — a same-leg, read-once
     * signal, exactly like {@link #resumePoint}, not a persisted fact about the
     * discussion. {@code null} means no ceiling has fired (the common case, and the
     * only possible value while {@code maxCostPerDiscussion} is unset).
     */
    @JsonIgnore
    private transient AgentGroupConfiguration.ProtocolConfig.CostPolicy costCeilingOutcome;

    /**
     * One accepted artifact write (I17), queued by the artifact tools on the LIVE
     * instance and drained by {@code MemberTurnExecutor} after the turn to fire the
     * {@code artifact_updated} event. This indirection exists because tools have no
     * listener reference — {@code ToolAssemblyContext} carries none — while the
     * turn executor does. Transient and concurrent: PARALLEL phases run members
     * (and so their tools) concurrently.
     */
    public record ArtifactChange(String artifactId, String name, String type, long version, String editorAgentId,
            String status, boolean created) {
    }

    @JsonIgnore
    private final transient Queue<ArtifactChange> pendingArtifactChanges = new ConcurrentLinkedQueue<>();

    /**
     * Serializes the drain HANDOFF over {@link #pendingArtifactChanges}. The queue
     * itself is safe, but two PARALLEL turns ending together would split it between
     * their drains and could then publish v2's event before v1's. The mutex is held
     * only around the drain and the publisher flag — never across the listener
     * callbacks, so a slow SSE client cannot block other turns' end-of-turn drains.
     * See {@code MemberTurnExecutor#announceArtifactChanges}.
     */
    @JsonIgnore
    private final transient Object artifactAnnounceMutex = new Object();

    /** The monitor {@code MemberTurnExecutor} serializes announce passes on. */
    @JsonIgnore
    public Object artifactAnnounceMutex() {
        return artifactAnnounceMutex;
    }

    /**
     * Whether a thread is currently PUBLISHING drained artifact changes. Guarded by
     * {@link #artifactAnnounceMutex} (never read or written outside it) — this flag
     * is what lets the mutex be released during the listener callbacks themselves:
     * the active publisher keeps looping over late arrivals, and every other thread
     * hands off and leaves instead of blocking on a slow SSE client. Deliberately
     * not {@code isX}-named: runtime coordination state, invisible to Jackson.
     */
    private transient boolean artifactAnnouncePublishing;

    @JsonIgnore
    public boolean artifactAnnouncePublishing() {
        return artifactAnnouncePublishing;
    }

    @JsonIgnore
    public void artifactAnnouncePublishing(boolean publishing) {
        this.artifactAnnouncePublishing = publishing;
    }

    /** Queues an accepted artifact write for the turn executor to announce. */
    @JsonIgnore
    public void queueArtifactChange(ArtifactChange change) {
        if (change != null) {
            pendingArtifactChanges.add(change);
        }
    }

    /** Drains queued artifact writes — each drained exactly once. */
    @JsonIgnore
    public List<ArtifactChange> drainArtifactChanges() {
        List<ArtifactChange> drained = new ArrayList<>();
        ArtifactChange change;
        while ((change = pendingArtifactChanges.poll()) != null) {
            drained.add(change);
        }
        return drained;
    }

    /**
     * The discussion's shared artifacts (I17), populated at READ time by
     * {@code GroupConversationService.readGroupConversation} from the artifact
     * store — never persisted with this document (artifacts have their own
     * collection; see {@link SharedArtifact}). Serialized when populated so REST's
     * status payload and MCP's {@code read_group_conversation} both carry it;
     * {@code READ_ONLY} because a stored copy must never be trusted back. Mirrors
     * the {@code availableActions} idiom.
     */
    @JsonIgnore
    private transient List<SharedArtifact> artifacts;

    @JsonProperty(value = "artifacts", access = JsonProperty.Access.READ_ONLY)
    public List<SharedArtifact> getArtifacts() {
        return artifacts;
    }

    @JsonIgnore
    public void setArtifacts(List<SharedArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    /**
     * A parent discussion's remaining cost budget at the moment it dispatched this
     * nested one (I1), or a cadence run's {@code maxCostPerRun} (I13), or
     * {@code null} for an unconstrained top-level discussion. The discussion runs
     * under {@code min(own configured ceiling, this)} — see
     * {@code GroupConversationService.effectiveCostCeiling}.
     * <p>
     * PERSISTED (final-review finding): this was transient, so every HITL
     * pause/resume re-read the document and silently DROPPED the ceiling — a
     * cadence discussion whose group config had no ceiling of its own resumed with
     * unlimited spend after one human approval. The ceiling is a property of the
     * RUN, and the run survives pauses.
     */
    private Double inheritedCostCeiling;

    /**
     * A single entry in the discussion transcript. Each entry records one agent's
     * contribution during a specific phase.
     *
     * @param speakerAgentId
     *            the agent that produced this entry
     * @param speakerDisplayName
     *            human-readable name
     * @param content
     *            the agent's response text
     * @param phaseIndex
     *            which phase produced this (0-indexed)
     * @param phaseName
     *            human-readable phase name, e.g. "Peer Critique"
     * @param type
     *            entry classification
     * @param timestamp
     *            when the entry was created
     * @param errorReason
     *            error detail if type is ERROR or SKIPPED
     * @param targetAgentId
     *            who this entry addresses (for CRITIQUE), null if broadcast
     * @param signature
     *            Base64-encoded Ed25519 signature if the agent has
     *            {@code signInterAgentMessages=true}, null otherwise
     * @param signatureNonce
     *            UUID nonce for replay protection (null if unsigned)
     * @param signatureTimestampMs
     *            epoch milliseconds when the envelope was signed (null if unsigned)
     * @param signatureKeyVersion
     *            version of the signing key used (null if unsigned)
     */
    public record TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
            TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId, String signature,
            String signatureNonce, Long signatureTimestampMs, Integer signatureKeyVersion) {

        /**
         * Backward-compatible constructor without any signature fields.
         */
        public TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
                TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId) {
            this(speakerAgentId, speakerDisplayName, content, phaseIndex, phaseName,
                    type, timestamp, errorReason, targetAgentId, null, null, null, null);
        }

        /**
         * Backward-compatible constructor with signature only (no envelope data).
         */
        public TranscriptEntry(String speakerAgentId, String speakerDisplayName, String content, int phaseIndex, String phaseName,
                TranscriptEntryType type, Instant timestamp, String errorReason, String targetAgentId, String signature) {
            this(speakerAgentId, speakerDisplayName, content, phaseIndex, phaseName,
                    type, timestamp, errorReason, targetAgentId, signature, null, null, null);
        }

        /**
         * Check whether this entry has full envelope data (signature + nonce +
         * timestamp) suitable for cryptographic verification.
         */
        public boolean hasEnvelopeData() {
            return signature != null && signatureNonce != null && signatureTimestampMs != null;
        }
    }

    public enum TranscriptEntryType {
        QUESTION, OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUMENT, REBUTTAL, SYNTHESIS, ERROR, SKIPPED,
        /** Task plan output from the PLAN phase. */
        PLAN,
        /** Task execution result from the EXECUTE phase. */
        TASK_RESULT,
        /** Verification assessment from the VERIFY phase. */
        VERIFICATION,
        /** User-to-member or member-to-user follow-up exchange between rounds. */
        FOLLOW_UP,
        /**
         * A speaker declined to add anything new this round (I4). Peer-hidden — see
         * {@code GroupContextBuilder#filterByScope}'s visibility matrix (Wave 0, F4).
         */
        ABSTAINED,
        /**
         * A member's recorded disagreement with a synthesis (I4). Peer-visible.
         */
        DISSENT,
        /**
         * A convergence judge's agreement-score result (I2). Peer-hidden.
         */
        CONVERGENCE,
        /**
         * A facilitator's bounded intervention (I12). Peer-hidden.
         */
        FACILITATION,
        /**
         * A cast ballot (I14). Peer-hidden while its own phase is still running (blind
         * ballot); visible once that phase completes.
         */
        VOTE,
        /**
         * A negotiation offer (I11). Peer-visible.
         */
        PROPOSAL,
        /**
         * A negotiation counter-offer or concession (I11). Peer-visible.
         */
        BARGAIN,
        /**
         * A human group member's contribution (I6). Peer-visible.
         */
        HUMAN_INPUT,
        /**
         * Retrospective phase output, feeding group memory (I8). Peer-visible.
         */
        RETRO,
        /**
         * A bid for a task assignment (I18). Peer-hidden while its own phase is still
         * running (blind bid); visible once that phase completes.
         */
        BID
    }

    /**
     * A pause landing inside a running SEQUENTIAL phase's speaker list, rather than
     * at a phase boundary (Wave 0, F2). Lets a resume skip the speakers that
     * already ran before the pause instead of re-running the whole phase.
     * <p>
     * <b>PARALLEL phases never produce one of these.</b> A parallel phase fans
     * every speaker out at once and joins at the end; there is no "speaker N of the
     * batch already ran, N+1 didn't" state to bookmark — a parallel resume always
     * re-runs the entire fan-out from the paused snapshot. That is idempotent by
     * design (each member turn is independent and the results only join once, at
     * the end), so re-running members that "already" produced a transcript entry
     * before the pause is not a correctness problem the way it would be for a
     * stateful sequential turn order — it is simply redundant LLM calls, which a
     * future optimization could avoid but this feature does not need to.
     *
     * @param phaseIdx
     *            the phase this pause happened inside — matched against
     *            {@code executeDiscussion}'s current phase before the speaker
     *            offset is honored, so a resume of a <em>different</em> phase (or
     *            of a discussion whose phase list changed underneath it) never
     *            misapplies a stale offset
     * @param repeatIdx
     *            which repeat of that phase (phases with {@code repeats() > 1}) the
     *            pause landed on. Only the repeat matching this value gets the
     *            {@code speakerIdx} offset — earlier repeats of the <em>same</em>
     *            phase are not skipped and replay from their own first speaker, the
     *            same way a phase-level ({@code
     *            HitlPauseType.TASK}) resume already replays a whole phase from its
     *            first speaker. A producer that pauses mid-repeat on a phase with
     *            {@code repeats() > 1} accepts that replay; a repeat-level start
     *            offset would avoid it but is outside this feature.
     * @param speakerIdx
     *            index into the phase's resolved speaker list of the speaker the
     *            pause landed on; on resume, speakers before this index are skipped
     *            rather than re-run
     * @param pauseKind
     *            which kind of mid-phase pause this bookmark records. For most
     *            kinds it is a pure observability tag (resume logic keys off this
     *            record's mere presence), with ONE exception:
     *            {@link #RESUME_KIND_HUMAN_TURN_PARALLEL} tells the resumed leg
     *            that {@code speakerIdx} indexes the phase's HUMAN-only sublist and
     *            that the agent fan-out already ran — the parallel executor then
     *            skips straight to the remaining human turns instead of re-running
     *            the fan-out (the sole carve-out from the "PARALLEL never honors a
     *            bookmark" rule above; see I6)
     */
    public record ResumePoint(int phaseIdx, int repeatIdx, int speakerIdx, String pauseKind) {
    }

    /** {@link ResumePoint#pauseKind()} of a sequential HUMAN member turn (I6). */
    public static final String RESUME_KIND_HUMAN_TURN = "HUMAN_TURN";

    /**
     * {@link ResumePoint#pauseKind()} of a HUMAN turn in a PARALLEL phase (I6):
     * {@code speakerIdx} indexes the phase's human-only sublist, and the resumed
     * leg must NOT re-run the agent fan-out.
     */
    public static final String RESUME_KIND_HUMAN_TURN_PARALLEL = "HUMAN_TURN_PARALLEL";

    /**
     * The one turn a paused {@code AWAITING_HUMAN_INPUT} discussion is waiting on
     * (I6). Persisted with the document so the prompt survives a restart and the
     * approval/inbox surfaces can display exactly what the member was asked.
     *
     * @param memberId
     *            the HUMAN member's {@code agentId} — the human principal id;
     *            submissions must come from this principal (or an admin)
     * @param displayName
     *            the member's display name, for transcript attribution and UI
     * @param phaseIdx
     *            phase the turn belongs to (matches the {@link ResumePoint})
     * @param repeatIdx
     *            repeat of that phase
     * @param speakerIdx
     *            the member's index — into the phase's resolved speaker list for
     *            sequential turns, into the human-only sublist for parallel ones
     * @param entryType
     *            {@link TranscriptEntryType} name the submitted content is recorded
     *            as — captured at pause time so a config edit while paused cannot
     *            re-type the entry (a human OPINION is an OPINION)
     * @param renderedPrompt
     *            the phase input rendered for this member exactly as an agent would
     *            have received it — what the UI shows the human
     * @param onTimeout
     *            the group's {@code humanMemberConfig.onTimeout} policy name at
     *            pause time (SKIP_TURN or ABORT) — bookmarked so crash recovery
     *            re-arms the same policy the pause promised, config edits
     *            notwithstanding
     * @param requestedAt
     *            when the turn was requested
     */
    public record PendingHumanInput(String memberId, String displayName, int phaseIdx, int repeatIdx, int speakerIdx,
            String entryType, String renderedPrompt, String onTimeout, Instant requestedAt) {
    }

    /**
     * The negotiation's working state (I11): open/superseded proposals and the
     * concession ledger. Persisted with the document — the ledger is the record,
     * quoted back into every bargaining turn (accountability is the anti-sycophancy
     * mechanism) and into the outcome.
     * <p>
     * Lists are copy-on-write for the same reason the roster lists are: Jackson
     * walks them unguarded on every persist while a turn may append.
     */
    public static class NegotiationState {

        private List<Proposal> proposals = new CopyOnWriteArrayList<>();
        private List<Concession> concessions = new CopyOnWriteArrayList<>();

        /**
         * Read-only view — all mutation goes through {@link #addProposal},
         * {@link #replaceProposal} and {@link #addConcession}, so the table cannot be
         * edited behind the state's back.
         */
        public List<Proposal> getProposals() {
            return Collections.unmodifiableList(proposals);
        }

        public void setProposals(List<Proposal> proposals) {
            this.proposals = proposals != null ? new CopyOnWriteArrayList<>(proposals) : new CopyOnWriteArrayList<>();
        }

        /** Read-only view — see {@link #getProposals}. */
        public List<Concession> getConcessions() {
            return Collections.unmodifiableList(concessions);
        }

        public void setConcessions(List<Concession> concessions) {
            this.concessions = concessions != null ? new CopyOnWriteArrayList<>(concessions) : new CopyOnWriteArrayList<>();
        }

        public void addProposal(Proposal proposal) {
            proposals.add(proposal);
        }

        /** In-place status/signature transition; a no-op if {@code oldOne} is gone. */
        public void replaceProposal(Proposal oldOne, Proposal newOne) {
            int idx = proposals.indexOf(oldOne);
            if (idx >= 0) {
                proposals.set(idx, newOne);
            }
        }

        public void addConcession(Concession concession) {
            concessions.add(concession);
        }
    }

    /**
     * One proposal on the negotiation table (I11).
     *
     * @param id
     *            stable id ("p1", "p2", …) — what a BARGAIN turn's {@code accept}
     *            names
     * @param byAgentId
     *            the proposer (implicitly the first acceptor of their own terms)
     * @param round
     *            the phase repeat the proposal was made in
     * @param terms
     *            the proposal's terms — a String in v1, deliberately untyped
     * @param status
     *            OPEN, SUPERSEDED (the proposer made a newer proposal) or REJECTED
     * @param acceptedBy
     *            agent ids that accepted these terms
     * @param acceptanceEntryIndices
     *            agentId → absolute transcript index of that agent's accepting
     *            entry. The signed transcript entries ARE the co-signatures — no
     *            new crypto; these indices are what the AGREEMENT decision quotes
     *            in {@code tally.signedAcceptances}
     */
    public record Proposal(String id, String byAgentId, int round, String terms, String status,
            List<String> acceptedBy, Map<String, Integer> acceptanceEntryIndices) {
    }

    /** Proposal status: still on the table. */
    public static final String PROPOSAL_OPEN = "OPEN";
    /** Proposal status: replaced by the proposer's own newer proposal. */
    public static final String PROPOSAL_SUPERSEDED = "SUPERSEDED";

    /**
     * One ledger line (I11): what an agent gave up, and what they received in
     * return — every concession must name its counterpart; that structure is what
     * stops sycophantic instant-agreement.
     */
    public record Concession(String byAgentId, int round, String gaveUp, String inReturnFor, String refProposalId) {
    }

    /**
     * What kind of conclusion a {@link DecisionRecord} represents (Wave 0, F3).
     */
    public enum DecisionType {
        /** A debate judged to a winner (I3). */
        VERDICT,
        /** A tallied ballot (I14). */
        VOTE,
        /** A negotiated compromise both sides accepted (I11). */
        AGREEMENT,
        /** A task/turn awarded by bid (I18). */
        AWARD,
        /** No structured decision was produced — prose-only conclusion. */
        NONE
    }

    /**
     * One member's recorded disagreement with a decision (Wave 0, F3).
     *
     * @param agentId
     *            the dissenting member
     * @param displayName
     *            human-readable name, for display without a roster lookup
     * @param position
     *            the member's own short statement of where they disagree
     */
    public record Dissent(String agentId, String displayName, String position) {
    }

    /**
     * Typed outcome of a discussion, or of one decision-producing phase within it
     * (Wave 0, F3). Today the only conclusion a discussion produces is
     * {@link #synthesizedAnswer}, which is always prose — callers that want to
     * branch on a winner, a tally, or a vote count have to parse it. This is the
     * structured alternative, populated by whichever decision-producing feature ran
     * (I3 verdicts, I11 agreements, I14 votes, I18 awards); {@code null} until one
     * of those exists.
     * <p>
     * A parse failure in the producing feature's own judgment/tally step must never
     * fail the discussion — the convention each of those features follows is to
     * fall back to {@code type=NONE} with {@link #raw} set to the unparsed text,
     * rather than to leave {@link GroupConversation#getDecision()} {@code null} and
     * lose the source material.
     *
     * @param type
     *            what kind of decision this is
     * @param outcome
     *            human-readable one-liner summarizing the result, for display
     *            without interpreting {@link #tally}
     * @param winner
     *            the winning side/option/agent, if this decision has one;
     *            {@code null} for a tie, a non-competitive agreement, or
     *            {@code type=NONE}
     * @param tally
     *            nullable structured detail specific to {@link #type} — option to
     *            weight for {@code VOTE}, side to score for {@code VERDICT}, bidder
     *            to bid for {@code AWARD}
     * @param dissents
     *            members who disagreed with this decision; empty (never
     *            {@code null}) when nobody dissented or dissent-recording is off
     * @param method
     *            free-text tag naming the mechanism that produced this decision,
     *            e.g. {@code "debate-judgment"}, {@code "majority"},
     *            {@code "approval"}, {@code "negotiation"}, {@code "arbitration"},
     *            {@code "bid-award"} — not an enum, since new methods are expected
     *            to be added by later features without touching this record
     * @param decidedAtPhase
     *            name of the phase that produced this decision
     * @param raw
     *            the unparsed source text the producing feature judged/tallied,
     *            kept for audit even when {@link #type} is {@code NONE} because
     *            parsing failed
     */
    public record DecisionRecord(DecisionType type, String outcome, String winner, Map<String, Object> tally, List<Dissent> dissents,
            String method, String decidedAtPhase, String raw) {
    }

    public enum GroupConversationState {
        CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED,
        /** Discussion was cancelled before completion — HITL foundation (Phase 9b). */
        CANCELLED,
        /** Paused for human approval — HITL foundation (Phase 9b). */
        AWAITING_APPROVAL,
        /**
         * Paused because a HUMAN group member's turn is up (I6). Deliberately NOT
         * {@link #AWAITING_APPROVAL}: approval endpoints must never accept free text,
         * and an inbox must be able to tell "approve/reject this" from "you're up".
         * Resolved only by {@code submitHumanInput} (or its timeout policy) — never by
         * the approve/resume surface.
         */
        AWAITING_HUMAN_INPUT,
        /**
         * Terminal — member conversations ended, ephemeral agents cleaned up, no
         * further follow-ups.
         */
        CLOSED
    }

    public enum HitlPauseType {
        PHASE, TASK,
        /**
         * A HUMAN group member's turn (I6) — see
         * {@link GroupConversationState#AWAITING_HUMAN_INPUT}.
         */
        HUMAN_TURN
    }

    // --- Getters/Setters ---

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public GroupConversationState getState() {
        return state;
    }

    public void setState(GroupConversationState state) {
        this.state = state;
    }

    public String getOriginalQuestion() {
        return originalQuestion;
    }

    public void setOriginalQuestion(String originalQuestion) {
        this.originalQuestion = originalQuestion;
    }

    public String getResumeQuestion() {
        return resumeQuestion;
    }

    public void setResumeQuestion(String resumeQuestion) {
        this.resumeQuestion = resumeQuestion;
    }

    public List<TranscriptEntry> getTranscript() {
        return transcript;
    }

    public void setTranscript(List<TranscriptEntry> transcript) {
        this.transcript = transcript != null
                ? Collections.synchronizedList(new ArrayList<>(transcript))
                : Collections.synchronizedList(new ArrayList<>());
    }

    public Map<String, String> getMemberConversationIds() {
        return memberConversationIds;
    }

    public void setMemberConversationIds(Map<String, String> memberConversationIds) {
        this.memberConversationIds = memberConversationIds != null
                ? new ConcurrentHashMap<>(memberConversationIds)
                : new ConcurrentHashMap<>();
    }

    public Map<String, Double> getMemberCosts() {
        return memberCosts;
    }

    public void setMemberCosts(Map<String, Double> memberCosts) {
        this.memberCosts = memberCosts != null ? new ConcurrentHashMap<>(memberCosts) : new ConcurrentHashMap<>();
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public int getCurrentPhaseIndex() {
        return currentPhaseIndex;
    }

    public void setCurrentPhaseIndex(int currentPhaseIndex) {
        this.currentPhaseIndex = currentPhaseIndex;
    }

    public String getCurrentPhaseName() {
        return currentPhaseName;
    }

    public void setCurrentPhaseName(String currentPhaseName) {
        this.currentPhaseName = currentPhaseName;
    }

    public String getSynthesizedAnswer() {
        return synthesizedAnswer;
    }

    public void setSynthesizedAnswer(String synthesizedAnswer) {
        this.synthesizedAnswer = synthesizedAnswer;
    }

    public DecisionRecord getDecision() {
        return decision;
    }

    public void setDecision(DecisionRecord decision) {
        this.decision = decision;
    }

    public NegotiationState getNegotiation() {
        return negotiation;
    }

    public void setNegotiation(NegotiationState negotiation) {
        this.negotiation = negotiation;
    }

    /** The negotiation state, created on first use (I11). */
    @JsonIgnore
    public NegotiationState negotiationState() {
        if (negotiation == null) {
            negotiation = new NegotiationState();
        }
        return negotiation;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public SharedTaskList getTaskList() {
        return taskList;
    }

    public void setTaskList(SharedTaskList taskList) {
        this.taskList = taskList;
    }

    public List<AgentGroupConfiguration.GroupMember> getDynamicMembers() {
        return dynamicMembers;
    }

    public void setDynamicMembers(List<AgentGroupConfiguration.GroupMember> dynamicMembers) {
        this.dynamicMembers = dynamicMembers != null
                ? new CopyOnWriteArrayList<>(dynamicMembers)
                : new CopyOnWriteArrayList<>();
    }

    /**
     * Add a dynamically recruited or created member to the conversation.
     * Thread-safe.
     */
    public void addDynamicMember(AgentGroupConfiguration.GroupMember member) {
        dynamicMembers.add(member);
    }

    public List<String> getCreatedAgentIds() {
        return createdAgentIds;
    }

    public List<String> getRecruitedAgentIds() {
        return recruitedAgentIds;
    }

    public void setRecruitedAgentIds(List<String> recruitedAgentIds) {
        this.recruitedAgentIds = recruitedAgentIds != null
                ? new CopyOnWriteArrayList<>(recruitedAgentIds)
                : new CopyOnWriteArrayList<>();
    }

    public void setCreatedAgentIds(List<String> createdAgentIds) {
        this.createdAgentIds = createdAgentIds != null
                ? new CopyOnWriteArrayList<>(createdAgentIds)
                : new CopyOnWriteArrayList<>();
    }

    public Set<String> getRetainedAgentIds() {
        return retainedAgentIds;
    }

    public void setRetainedAgentIds(Set<String> retainedAgentIds) {
        Set<String> newSet = ConcurrentHashMap.newKeySet();
        if (retainedAgentIds != null) {
            newSet.addAll(retainedAgentIds);
        }
        this.retainedAgentIds = newSet;
    }

    public Set<String> getTornDownAgentIds() {
        return tornDownAgentIds;
    }

    public void setTornDownAgentIds(Set<String> tornDownAgentIds) {
        Set<String> newSet = ConcurrentHashMap.newKeySet();
        if (tornDownAgentIds != null) {
            newSet.addAll(tornDownAgentIds);
        }
        this.tornDownAgentIds = newSet;
    }

    /**
     * Records a teardown and drops the agent from every live tracking list, as one
     * operation.
     * <p>
     * The tombstone is written FIRST. A concurrent
     * {@code propagateDynamicAgentTracking} merging a stale snapshot would
     * otherwise be able to re-add the id between the removals and the tombstone,
     * and the merge consults the tombstone precisely to refuse that.
     *
     * @return {@code true} if this call was the one that recorded the teardown
     */
    public boolean recordTeardown(String agentId) {
        if (agentId == null) {
            return false;
        }
        boolean first = tornDownAgentIds.add(agentId);
        createdAgentIds.remove(agentId);
        retainedAgentIds.remove(agentId);
        return first;
    }

    @JsonIgnore
    public AgentGroupConfiguration.DynamicAgentConfig getDynamicAgentConfig() {
        return dynamicAgentConfig;
    }

    public void setDynamicAgentConfig(AgentGroupConfiguration.DynamicAgentConfig dynamicAgentConfig) {
        this.dynamicAgentConfig = dynamicAgentConfig;
    }

    public Map<String, String> getMemberDisplayNames() {
        return Collections.unmodifiableMap(memberDisplayNames);
    }

    /**
     * ConcurrentHashMap on BOTH branches, matching the field initialiser.
     * <p>
     * The setter installed a {@link LinkedHashMap}, so a conversation loaded from
     * the store — every resume, every read-repair — silently lost the concurrency
     * guarantee the field declares. That map is written from member-turn threads
     * ({@code RecruitAgentTool}) and iterated by serialization, and
     * {@link #addMemberDisplayNameIfAbsent} depends on {@code putIfAbsent} being
     * atomic, which on a LinkedHashMap it is not.
     */
    public void setMemberDisplayNames(Map<String, String> memberDisplayNames) {
        this.memberDisplayNames = memberDisplayNames != null
                ? new ConcurrentHashMap<>(memberDisplayNames)
                : new ConcurrentHashMap<>();
    }

    /**
     * Register a member's display name (agentId → displayName). Used at discussion
     * start to populate the map. This is the supported mutation path —
     * {@link #getMemberDisplayNames()} returns an unmodifiable view.
     */
    public void addMemberDisplayName(String agentId, String displayName) {
        this.memberDisplayNames.put(agentId, displayName);
    }

    /**
     * Records a display name only if the agent has none yet.
     * <p>
     * The seeding pass at the top of {@code executeDiscussion} fills this map from
     * the CONFIGURED roster, where the names are operator-chosen. A later writer
     * that only knows an agent id — {@code RecruitAgentTool}, which passes the id
     * as the name — must not overwrite one of those; doing so replaced a member's
     * real name with a raw id everywhere it is rendered, including the "which
     * member did you mean?" list {@code followUpWithMember} produces.
     */
    public void addMemberDisplayNameIfAbsent(String agentId, String displayName) {
        this.memberDisplayNames.putIfAbsent(agentId, displayName);
    }

    /**
     * Computed property derived from {@link #state} — tells clients which
     * operations are available. It is serialized (so REST/MCP clients see it, and
     * it therefore also lands in the stored document), but {@code READ_ONLY} access
     * means it is never read back in: the value is always recomputed from
     * {@code state}, so a stale value in an old document cannot be trusted or used.
     */
    @JsonProperty(value = "availableActions", access = JsonProperty.Access.READ_ONLY)
    public List<String> getAvailableActions() {
        if (state == null) {
            return List.of();
        }
        return switch (state) {
            case COMPLETED -> List.of("followup", "continue", "close");
            // FAILED and CANCELLED are terminal but closeable — close ends member
            // conversations and reclaims ephemeral agents.
            case FAILED, CANCELLED -> List.of("close");
            // I6: the one state a human member acts on — the UI switches to an
            // input prompt instead of approve/reject buttons.
            case AWAITING_HUMAN_INPUT -> List.of("submitHumanInput");
            case IN_PROGRESS, SYNTHESIZING, CREATED, AWAITING_APPROVAL -> List.of();
            case CLOSED -> List.of();
        };
    }

    @JsonIgnore
    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    @JsonIgnore
    public boolean isPaused() {
        return pausedAt != null;
    }

    public int getPausedAtPhaseIndex() {
        return pausedAtPhaseIndex;
    }

    public void setPausedAtPhaseIndex(int pausedAtPhaseIndex) {
        this.pausedAtPhaseIndex = pausedAtPhaseIndex;
    }

    public int getPausedTurnCount() {
        return pausedTurnCount;
    }

    public void setPausedTurnCount(int pausedTurnCount) {
        this.pausedTurnCount = pausedTurnCount;
    }

    public String getPausedPhaseName() {
        return pausedPhaseName;
    }

    public void setPausedPhaseName(String pausedPhaseName) {
        this.pausedPhaseName = pausedPhaseName;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public HitlPauseType getHitlPauseType() {
        return hitlPauseType;
    }

    public void setHitlPauseType(HitlPauseType hitlPauseType) {
        this.hitlPauseType = hitlPauseType;
    }

    public String getHitlPauseReason() {
        return hitlPauseReason;
    }

    public void setHitlPauseReason(String hitlPauseReason) {
        this.hitlPauseReason = hitlPauseReason;
    }

    public ResumePoint getResumePoint() {
        return resumePoint;
    }

    public void setResumePoint(ResumePoint resumePoint) {
        this.resumePoint = resumePoint;
    }

    public PendingHumanInput getPendingHumanInput() {
        return pendingHumanInput;
    }

    public void setPendingHumanInput(PendingHumanInput pendingHumanInput) {
        this.pendingHumanInput = pendingHumanInput;
    }

    public AgentGroupConfiguration.ProtocolConfig.CostPolicy getCostCeilingOutcome() {
        return costCeilingOutcome;
    }

    public void setCostCeilingOutcome(AgentGroupConfiguration.ProtocolConfig.CostPolicy costCeilingOutcome) {
        this.costCeilingOutcome = costCeilingOutcome;
    }

    public Double getInheritedCostCeiling() {
        return inheritedCostCeiling;
    }

    public void setInheritedCostCeiling(Double inheritedCostCeiling) {
        this.inheritedCostCeiling = inheritedCostCeiling;
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

    public String getHitlLastPauseFingerprint() {
        return hitlLastPauseFingerprint;
    }

    public void setHitlLastPauseFingerprint(String hitlLastPauseFingerprint) {
        this.hitlLastPauseFingerprint = hitlLastPauseFingerprint;
    }
}
