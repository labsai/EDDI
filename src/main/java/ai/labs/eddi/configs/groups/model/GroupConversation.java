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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
     * Version 4 (I11): {@link #negotiationState} — resume-critical bargaining
     * state. An older deployment resuming (or merely re-saving) a paused v4
     * document would silently drop the proposals, signed acceptances and the
     * concession ledger, and the agreement check would then run against an empty
     * table — exactly the class of corruption the newer-than-current refusal exists
     * to prevent. No migration entry: v3 documents have no negotiation state and
     * the field defaults correctly via Jackson (identity hop).
     */
    public static final int CURRENT_SCHEMA_VERSION = 4;
    /**
     * The shape this specific document was last written in. Checked before a
     * resume: newer than {@link #CURRENT_SCHEMA_VERSION} refuses (this deployment
     * predates the document), older runs registered migrations forward. A pause can
     * sit in storage for days — long enough for a deploy to land in between — so
     * this is the group side's document-shape guard, alongside the existing
     * per-resume config-drift check (bookmarked phase vs. current config) that
     * guards a different axis. See {@code GroupConversationSchemaMigrations}.
     */
    private int schemaVersion = CURRENT_SCHEMA_VERSION;
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
     * Per-member dollar-cost attribution (Wave 0, F5): agentId → that member's cost
     * so far. For an individual agent, its own private conversation's cumulative
     * tracked cost; for a {@code MemberType.GROUP} member, the child discussion's
     * own {@link #totalCost} rolled up whole. See {@code GroupCostLedger}'s Javadoc
     * for the known gap this reflects (V1: a non-cascade member turn's own
     * model-completion cost is not tracked anywhere yet — I1 closes that).
     * Thread-safe: PARALLEL phases accumulate from multiple member turns
     * concurrently.
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
    private int pausedAtPhaseIndex = -1;
    private int pausedTurnCount = 0;
    private String pausedPhaseName;
    private Instant pausedAt;
    private HitlPauseType hitlPauseType;
    /** Human-readable reason for the pause (from HITL gate). */
    private String hitlPauseReason;
    /**
     * Where inside a SEQUENTIAL phase's speaker list a pause landed (Wave 0, F2).
     * {@code null} for every pause today — {@code PHASE} and {@code TASK} pauses
     * (the only kinds that exist) both land at a phase/task boundary, never
     * mid-speaker-list. Exists for I6 (human as a group member), which pauses
     * between one speaker and the next within a running SEQUENTIAL phase; see
     * {@link ResumePoint}'s own Javadoc for why PARALLEL phases never set this.
     */
    private ResumePoint resumePoint;
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
     * A parent discussion's remaining cost budget at the moment it dispatched this
     * nested one (I1), or {@code null} for a top-level discussion (and for a parent
     * that has no ceiling of its own). The discussion runs under
     * {@code min(own configured ceiling, this)} — see
     * {@code GroupConversationService.effectiveCostCeiling}. Transient: it is a
     * property of one dispatch, not of the stored discussion.
     */
    @JsonIgnore
    private transient Double inheritedCostCeiling;

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
     *            free-text tag for observability (REST/MCP status payloads) — not
     *            consulted by any resume logic, which keys off this record's mere
     *            presence rather than what kind of mid-phase pause it names
     */
    public record ResumePoint(int phaseIdx, int repeatIdx, int speakerIdx, String pauseKind) {
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
         * Terminal — member conversations ended, ephemeral agents cleaned up, no
         * further follow-ups.
         */
        CLOSED
    }

    public enum HitlPauseType {
        PHASE, TASK
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

    public void setMemberDisplayNames(Map<String, String> memberDisplayNames) {
        this.memberDisplayNames = memberDisplayNames != null
                ? new LinkedHashMap<>(memberDisplayNames)
                : new LinkedHashMap<>();
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
