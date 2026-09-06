/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlRejectionPolicy;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Versioned configuration for a group of agents that can participate in
 * structured discussions using configurable phase-based styles. Persisted via
 * {@code AbstractResourceStore}.
 *
 * @author ginccc
 */
public class AgentGroupConfiguration {

    /**
     * Upper bound on {@link #members}. Every member is one LLM call per phase, so
     * the member list sizes the discussion's fan-out directly. 100 is far beyond
     * any deliberated group (the dynamic-agent guardrails default to 5 created / 10
     * recruited members) while still bounding a pathological list.
     */
    public static final int MAX_MEMBERS = 100;

    /**
     * Upper bound on {@link #maxRounds}. {@code DiscussionStylePresets.expand}
     * multiplies this into concrete phases for ROUND_TABLE and DELPHI, and every
     * phase fans out to every member — so this value alone multiplies the LLM cost
     * of a single discussion. 50 rounds against even a small group is already
     * hundreds of model calls; nothing legitimate goes near it, and without the
     * bound a config could ask for millions of phases.
     * <p>
     * Deliberately only an upper bound: {@code expand()} already clamps the low
     * side with {@code Math.max(maxRounds, 1)}, so rejecting 0 would break configs
     * that legitimately leave it unset for round-less styles such as DEBATE.
     */
    public static final int MAX_DISCUSSION_ROUNDS = 50;

    private String name;
    private String description;

    @Size(max = MAX_MEMBERS, message = "'members' must contain at most {max} entries")
    private List<GroupMember> members = new ArrayList<>();
    private String moderatorAgentId;
    private DiscussionStyle style;

    @Max(value = MAX_DISCUSSION_ROUNDS, message = "'maxRounds' must be at most {value}")
    private int maxRounds = 2;
    private List<DiscussionPhase> phases;
    private ProtocolConfig protocol;
    /** Pre-configured task list. If non-empty, skips the PLAN phase. */
    private List<TaskDefinition> tasks;
    /** Dynamic agent creation and recruitment configuration. */
    private DynamicAgentConfig dynamicAgents;
    /**
     * After each SYNTHESIS phase, give every participant who did NOT write the
     * synthesis one short turn to state where they still materially disagree (I4).
     * Non-PASS replies become public {@code DISSENT} transcript entries and
     * populate {@code DecisionRecord.dissents}.
     * <p>
     * Opt-in because it costs one extra short call per non-synthesizer. It is
     * nonetheless the honest design: the alternative is asking the synthesizer to
     * report the disagreement with its own synthesis, and an author summarizing the
     * objections to their own summary is exactly the failure mode a minority report
     * exists to prevent.
     */
    private boolean recordDissents;

    /**
     * Whether and how far members may file their own tasks mid-discussion (I5).
     * {@code null} means the tools are absent entirely, which is also what a
     * default-constructed {@link GroupTaskConfig} means.
     */
    private GroupTaskConfig taskListConfig;

    public GroupTaskConfig getTaskListConfig() {
        return taskListConfig;
    }

    public void setTaskListConfig(GroupTaskConfig taskListConfig) {
        this.taskListConfig = taskListConfig;
    }

    /**
     * How HUMAN members' turns are timed out (I6). {@code null} means wait
     * indefinitely — the same thing a default-constructed {@link HumanMemberConfig}
     * means.
     */
    private HumanMemberConfig humanMemberConfig;

    public HumanMemberConfig getHumanMemberConfig() {
        return humanMemberConfig;
    }

    public void setHumanMemberConfig(HumanMemberConfig humanMemberConfig) {
        this.humanMemberConfig = humanMemberConfig;
    }

    /**
     * Bounds for RETRO lessons (I8). {@code null} runs a RETRO phase with the
     * defaults — the caps are non-negotiable either way (bounded growth for an LLM
     * write surface).
     */
    private RetroConfig retroConfig;

    public RetroConfig getRetroConfig() {
        return retroConfig;
    }

    public void setRetroConfig(RetroConfig retroConfig) {
        this.retroConfig = retroConfig;
    }

    /**
     * Bounds for the RETRO lesson pipeline (I8).
     *
     * @param maxLessonsPerRun
     *            how many lessons one retro turn may store (default 3) — the
     *            template quotes this cap to the model, and the parser truncates
     *            past it regardless
     * @param maxStoredLessons
     *            FIFO ceiling on the team's stored lessons (default 50): storing
     *            the 51st evicts the oldest. Bounded growth is non-negotiable —
     *            non-positive values fall back to the defaults
     */
    public record RetroConfig(int maxLessonsPerRun, int maxStoredLessons) {

        public static final int DEFAULT_MAX_PER_RUN = 3;
        public static final int DEFAULT_MAX_STORED = 50;
        /**
         * Hard ceilings (review finding): the compact constructor accepted any positive
         * int, so a config carrying {@code Integer.MAX_VALUE} made the per-run write
         * count and the retained-lesson count effectively unbounded — exactly the
         * bounded-growth guarantee this record exists to enforce on an LLM-driven write
         * surface.
         */
        public static final int CEILING_MAX_PER_RUN = 20;
        public static final int CEILING_MAX_STORED = 500;

        /** Same normalization choke point as {@link GroupTaskConfig}. */
        public RetroConfig {
            if (maxLessonsPerRun <= 0) {
                maxLessonsPerRun = DEFAULT_MAX_PER_RUN;
            }
            if (maxStoredLessons <= 0) {
                maxStoredLessons = DEFAULT_MAX_STORED;
            }
            maxLessonsPerRun = Math.min(maxLessonsPerRun, CEILING_MAX_PER_RUN);
            maxStoredLessons = Math.min(maxStoredLessons, CEILING_MAX_STORED);
        }

        /** Both caps at their defaults. */
        public RetroConfig() {
            this(DEFAULT_MAX_PER_RUN, DEFAULT_MAX_STORED);
        }
    }

    /**
     * Transcript windowing for rendered member context (I9). {@code null} means
     * windowing is off — every FULL/ANONYMOUS-scope turn renders the whole
     * scope-filtered transcript, exactly as before the feature existed.
     */
    private ContextWindowConfig contextWindow;

    public ContextWindowConfig getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(ContextWindowConfig contextWindow) {
        this.contextWindow = contextWindow;
    }

    /**
     * Bounds what a FULL/ANONYMOUS-scope member turn renders from the transcript
     * (I9). Without it, every such turn re-feeds the entire transcript to every
     * member — ~quadratic cost as the discussion grows. When the scope-filtered
     * context exceeds {@link #maxRecentEntries}, the rendered context becomes
     * {@code [summary of the older entries] + [the recent entries verbatim]} — a
     * derived view only; the stored transcript is never modified and signing
     * verification still operates on the raw entries.
     *
     * @param enabled
     *            master switch, default off — a config that does not opt in renders
     *            exactly as before
     * @param maxRecentEntries
     *            how many of the newest scope-filtered entries stay verbatim.
     *            Non-positive falls back to {@link #DEFAULT_MAX_RECENT_ENTRIES} (an
     *            accidental 0 must not blank the whole context)
     * @param summarizeOverflow
     *            {@code true} (default) compresses the older entries into a rolling
     *            summary via the shared summarization service; {@code false}
     *            replaces them with a plain "[n earlier entries omitted]" marker
     *            and never calls an LLM
     * @param llmProvider
     *            provider for the summarization calls (e.g. "openai"). Required for
     *            summarization — without it (or {@code llmModel}) the overflow
     *            falls back to the truncation marker with a warning
     * @param llmModel
     *            model name for the summarization calls
     * @param inputPricePer1M
     *            optional USD price per 1M input tokens for the summarizer's own
     *            calls, so their cost counts toward the discussion's I1 ledger.
     *            Null or negative = unpriced ($0), same semantics as the LLM task
     *            pricing fields
     * @param outputPricePer1M
     *            optional USD price per 1M output tokens, same semantics
     */
    public record ContextWindowConfig(boolean enabled, int maxRecentEntries, Boolean summarizeOverflow,
            String llmProvider, String llmModel,
            Double inputPricePer1M, Double outputPricePer1M) {

        public static final int DEFAULT_MAX_RECENT_ENTRIES = 30;

        /**
         * Normalizes at the one choke point every reader passes through — same shape as
         * {@link GroupTaskConfig}. {@code summarizeOverflow} is a Boolean rather than a
         * boolean so an omitted JSON key defaults to {@code true} instead of silently
         * disabling summarization.
         */
        public ContextWindowConfig {
            if (maxRecentEntries <= 0) {
                maxRecentEntries = DEFAULT_MAX_RECENT_ENTRIES;
            }
            summarizeOverflow = summarizeOverflow == null || summarizeOverflow;
            // Blank identifiers ARE absent — every reader null-checks, and a
            // whitespace-only provider reaching the summarization service would
            // bypass the documented truncation fallback.
            llmProvider = llmProvider == null || llmProvider.isBlank() ? null : llmProvider;
            llmModel = llmModel == null || llmModel.isBlank() ? null : llmModel;
            inputPricePer1M = inputPricePer1M == null || inputPricePer1M < 0 ? null : inputPricePer1M;
            outputPricePer1M = outputPricePer1M == null || outputPricePer1M < 0 ? null : outputPricePer1M;
        }
    }

    /**
     * Governs agent-filed tasks (I5). The task list is otherwise written only by
     * the PLAN phase and by config, so work an agent <em>discovers</em> while
     * executing — a missing migration, an untested edge case — dies in prose. The
     * wave loop already re-queries {@code findExecutableTasks()} each wave, so a
     * filed task flows into execution with no scheduler changes.
     * <p>
     * <b>Off by default, and deliberately so.</b> An LLM that can file work can
     * file it in a loop; the caps below are the bound. There is no permissive
     * standalone default anywhere — a discussion that does not opt in never sees
     * the tools at all.
     *
     * @param allowAgentTaskCreation
     *            master switch. {@code false} (default) means the tools are not
     *            assembled, not merely rejected at call time — an absent tool costs
     *            no prompt tokens and cannot be argued with
     * @param maxAgentAddedTasksPerDiscussion
     *            ceiling on agent-filed tasks across the whole discussion (default
     *            20). Non-positive values fall back to the default rather than
     *            meaning "unlimited": an unbounded write surface for an LLM is
     *            never the intent behind a mistyped 0
     * @param maxPerTurn
     *            ceiling within a single member turn (default 3). Separate from the
     *            discussion cap because the failure modes differ — a runaway turn
     *            files twenty tasks in one breath, where a discussion-long drift
     *            files one per turn for forty turns
     */
    public record GroupTaskConfig(boolean allowAgentTaskCreation, int maxAgentAddedTasksPerDiscussion, int maxPerTurn,
            AssignmentMode assignmentMode) {

        public static final int DEFAULT_MAX_PER_DISCUSSION = 20;
        public static final int DEFAULT_MAX_PER_TURN = 3;

        /**
         * Normalizes at the one choke point every reader passes through, so no consumer
         * re-derives defaults from a partially-specified config — a JSON naming only
         * {@code allowAgentTaskCreation} is the common case. Same shape as
         * {@link ConvergenceConfig}'s compact constructor.
         */
        public GroupTaskConfig {
            if (maxAgentAddedTasksPerDiscussion <= 0) {
                maxAgentAddedTasksPerDiscussion = DEFAULT_MAX_PER_DISCUSSION;
            }
            if (maxPerTurn <= 0) {
                maxPerTurn = DEFAULT_MAX_PER_TURN;
            }
            if (assignmentMode == null) {
                assignmentMode = AssignmentMode.ROLE;
            }
        }

        /** Backward-compatible constructor without {@code assignmentMode} (I18). */
        public GroupTaskConfig(boolean allowAgentTaskCreation, int maxAgentAddedTasksPerDiscussion, int maxPerTurn) {
            this(allowAgentTaskCreation, maxAgentAddedTasksPerDiscussion, maxPerTurn, AssignmentMode.ROLE);
        }

        /** Disabled, with both caps at their defaults. */
        public GroupTaskConfig() {
            this(false, DEFAULT_MAX_PER_DISCUSSION, DEFAULT_MAX_PER_TURN);
        }
    }

    /**
     * A member of the group. Members can be individual agents, nested groups, or
     * humans (I6). Whether members may create and co-edit shared artifacts
     * mid-discussion (I17). {@code null} means the artifact tools are absent
     * entirely.
     */
    private ArtifactConfig artifactConfig;

    public ArtifactConfig getArtifactConfig() {
        return artifactConfig;
    }

    public void setArtifactConfig(ArtifactConfig artifactConfig) {
        this.artifactConfig = artifactConfig;
    }

    /**
     * Governs shared artifacts (I17, blackboard-lite): typed documents members
     * co-edit through tools instead of re-parsing each other's prose. Same
     * opt-in-by-absence discipline as {@link GroupTaskConfig}: off means the tools
     * are never assembled, and there is no permissive standalone default.
     *
     * @param allowArtifactTools
     *            master switch, default off
     * @param maxArtifactsPerDiscussion
     *            ceiling on artifacts per discussion (default 5). Non-positive
     *            falls back to the default — 0 must never mean unlimited for an LLM
     *            write surface
     * @param validators
     *            declarative validation chain every accepted write must pass —
     *            {@link ValidatorKind#JSON_SCHEMA}, {@link ValidatorKind#REGEX} or
     *            {@link ValidatorKind#MAX_LENGTH} with a {@code spec}. Declarative
     *            <em>only</em>, never arbitrary code. Failed validation rejects the
     *            write with the validator's message; nothing is stored
     */
    public record ArtifactConfig(boolean allowArtifactTools, int maxArtifactsPerDiscussion, List<ArtifactValidator> validators) {

        public static final int DEFAULT_MAX_ARTIFACTS = 5;

        /** Same normalization choke point as {@link GroupTaskConfig}. */
        public ArtifactConfig {
            if (maxArtifactsPerDiscussion <= 0) {
                maxArtifactsPerDiscussion = DEFAULT_MAX_ARTIFACTS;
            }
            // Not List.copyOf: it NPEs on a null ELEMENT ("validators": [null]),
            // preempting ArtifactValidators.requireValidSpecs' actionable message.
            validators = validators == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(validators));
        }

        /** Disabled, cap at its default, no validators. */
        public ArtifactConfig() {
            this(false, DEFAULT_MAX_ARTIFACTS, List.of());
        }
    }

    /**
     * One declarative artifact validator (I17): {@code kind} selects the check,
     * {@code spec} parameterizes it — a JSON schema document, a regex the content
     * must match, or a maximum character count. Specs are validated at save time so
     * a typo fails the config save, not a member's turn.
     */
    public record ArtifactValidator(ValidatorKind kind, String spec) {
    }

    /** The closed set of declarative artifact validators (I17). */
    public enum ValidatorKind {
        JSON_SCHEMA, REGEX, MAX_LENGTH
    }

    /**
     * A member of the group. Members can be individual agents or nested groups.
     * <p>
     * For {@code MemberType.GROUP} members, the {@code agentId} field contains the
     * group configuration ID instead. The sub-group runs its own discussion and its
     * synthesized answer becomes this member's response.
     * <p>
     * For {@code MemberType.HUMAN} members, {@code agentId} carries the human's
     * principal id (the identity that may submit their turns) and
     * {@code displayName} is required at save time — a paused discussion must be
     * able to say WHO it is waiting on.
     * <p>
     * The optional {@code role} field controls which phases the member participates
     * in (e.g. "DEVIL_ADVOCATE", "PRO", "CON"). If null, the member is a default
     * participant.
     */
    public record GroupMember(String agentId, String displayName, Integer speakingOrder, String role, MemberType memberType) {

        /** Convenience constructor defaulting to AGENT member type. */
        public GroupMember(String agentId, String displayName, Integer speakingOrder, String role) {
            this(agentId, displayName, speakingOrder, role, MemberType.AGENT);
        }
    }

    /**
     * Whether a group member is an individual agent, a nested sub-group, or a human
     * (I6).
     */
    public enum MemberType {
        /** An individual EDDI agent. */
        AGENT,
        /** A nested group — runs its own discussion, returns synthesized answer. */
        GROUP,
        /**
         * A human — their turn pauses the discussion ({@code
         * AWAITING_HUMAN_INPUT}) until they submit a response or the group's
         * {@code humanMemberConfig} timeout policy resolves the turn (I6).
         */
        HUMAN
    }

    /**
     * How the discussion treats HUMAN members' turns (I6). One config for the whole
     * group: humans on the same team wait under the same rules.
     *
     * @param turnTimeout
     *            ISO-8601 duration a human turn may stay unanswered before
     *            {@code onTimeout} fires; {@code null} or blank = wait indefinitely
     * @param onTimeout
     *            what an expired turn does — defaults to
     *            {@link OnHumanTimeout#SKIP_TURN}
     */
    public record HumanMemberConfig(String turnTimeout, OnHumanTimeout onTimeout) {

        /** Normalization choke point, same shape as {@link GroupTaskConfig}. */
        public HumanMemberConfig {
            if (onTimeout == null) {
                onTimeout = OnHumanTimeout.SKIP_TURN;
            }
        }

        /** Wait indefinitely; a timeout would skip the turn if one were set. */
        public HumanMemberConfig() {
            this(null, OnHumanTimeout.SKIP_TURN);
        }
    }

    /** What an expired human turn does (I6). */
    public enum OnHumanTimeout {
        /**
         * Record a SKIPPED entry ("no response from <name> within <d>") and move on.
         */
        SKIP_TURN,
        /** Cancel the discussion. */
        ABORT
    }

    /**
     * A facilitator agent's checkpoint configuration (I12). {@code null} means no
     * facilitator — the same thing a default-constructed instance means.
     */
    private FacilitatorConfig facilitator;

    public FacilitatorConfig getFacilitator() {
        return facilitator;
    }

    public void setFacilitator(FacilitatorConfig facilitator) {
        this.facilitator = facilitator;
    }

    /**
     * Bounded adaptive orchestration (I12): a facilitator agent is briefed at
     * configured checkpoints and chooses one move from a config-enumerated list. A
     * full LLM orchestrator conflicts with deterministic governance — the
     * facilitator never free-forms; it selects, and every selection is validated,
     * capped and audit-logged. Anything unparseable, disallowed or invalid degrades
     * to {@link FacilitatorMove#CONTINUE}.
     *
     * @param enabled
     *            master switch; {@code false} (default) means no checkpoint ever
     *            runs and the discussion costs nothing extra
     * @param agentId
     *            the agent that plays facilitator — required when enabled. It runs
     *            under its own conversation key, never a member's
     * @param allowedMoves
     *            the moves the facilitator may execute; defaults to
     *            {@code [CONTINUE]}, which makes an enabled-but-unconfigured
     *            facilitator a pure observer. {@code END_PHASE} and
     *            {@code EXTEND_PHASE} act mid-phase and are save-time rejected
     *            unless {@code checkAfter} is {@code EACH_REPEAT}
     * @param checkAfter
     *            checkpoint cadence — after each completed phase (default) or after
     *            each completed repeat within a phase
     * @param maxMovesPerDiscussion
     *            ceiling on <em>executed non-CONTINUE</em> moves across the whole
     *            discussion (default 10). Rejected attempts do not consume it —
     *            they are recorded, not budgeted
     * @param escalateTo
     *            principal id that {@link FacilitatorMove#ESCALATE_HUMAN} pauses
     *            for — required at save time when that move is allowed
     */
    public record FacilitatorConfig(boolean enabled, String agentId, List<FacilitatorMove> allowedMoves,
            FacilitatorCheckpoint checkAfter, int maxMovesPerDiscussion, String escalateTo) {

        public static final int DEFAULT_MAX_MOVES = 10;

        /** Normalization choke point, same shape as {@link GroupTaskConfig}. */
        public FacilitatorConfig {
            allowedMoves = allowedMoves == null || allowedMoves.isEmpty()
                    ? List.of(FacilitatorMove.CONTINUE)
                    : List.copyOf(allowedMoves);
            if (checkAfter == null) {
                checkAfter = FacilitatorCheckpoint.EACH_PHASE;
            }
            if (maxMovesPerDiscussion <= 0) {
                maxMovesPerDiscussion = DEFAULT_MAX_MOVES;
            }
        }

        /** Disabled, observer-only move list, phase-boundary cadence. */
        public FacilitatorConfig() {
            this(false, null, List.of(FacilitatorMove.CONTINUE), FacilitatorCheckpoint.EACH_PHASE, DEFAULT_MAX_MOVES, null);
        }
    }

    /**
     * The moves a facilitator may choose from (I12). The move list IS the feature
     * surface — each non-CONTINUE move drives machinery another item already built
     * (I2's phase-exit plumbing, I14's vote phases, I7's recruitment, I6's pending
     * human input).
     */
    public enum FacilitatorMove {
        /** No intervention — the ambient default and every failure's fallback. */
        CONTINUE,
        /** Skip the current phase's remaining repeats. Mid-phase only. */
        END_PHASE,
        /**
         * Add one repeat to the current phase (at most 2 extensions per phase, still
         * bounded by {@code maxTurns}). Mid-phase only.
         */
        EXTEND_PHASE,
        /**
         * Insert a one-off VOTE phase right after the current one, with the options the
         * facilitator names in {@code args.options}.
         */
        CALL_VOTE,
        /**
         * Recruit an existing deployed agent into the roster — the same validation path
         * as I7's {@code recruitAgent} tool.
         */
        RECRUIT,
        /**
         * Pause the discussion for input from the configured {@code escalateTo}
         * principal, carrying the facilitator's question (I6 pending-input machinery).
         */
        ESCALATE_HUMAN
    }

    /** When the facilitator is briefed (I12). */
    public enum FacilitatorCheckpoint {
        /** After each completed phase — the default. */
        EACH_PHASE,
        /**
         * After each completed repeat within a phase. Required for
         * {@code END_PHASE}/{@code EXTEND_PHASE}, which act mid-phase.
         */
        EACH_REPEAT
    }

    // --- Discussion Style ---

    /**
     * Preset discussion styles that auto-generate phases via
     * {@link DiscussionStylePresets}.
     */
    public enum DiscussionStyle {
        /** Everyone speaks → respond to others → repeat → synthesis. */
        ROUND_TABLE,
        /** Opinion → critique each peer → revise based on feedback → synthesis. */
        PEER_REVIEW,
        /** Opinion → devil challenges consensus → defend/revise → synthesis. */
        DEVIL_ADVOCATE,
        /** Anonymous independent rounds → gradual convergence → synthesis. */
        DELPHI,
        /** Pro team argues → Con team argues → rebuttals → judge decides. */
        DEBATE,
        /** Collaborative task accomplishment: plan → execute → verify → synthesis. */
        TASK_FORCE,
        /**
         * Trade, not win/lose (I11): positions & interests → opening proposals →
         * bargaining with a concession ledger → arbitration (skipped once an agreement
         * is reached) → synthesis.
         */
        NEGOTIATION,
        /** User defines phases manually. */
        CUSTOM
    }

    // --- Discussion Phase ---

    /**
     * A single phase in a discussion. Each phase defines who speaks, what context
     * they receive, and what prompt template to use.
     *
     * <p>
     * The {@code participants} field accepts: "ALL", "MODERATOR", or
     * "ROLE:&lt;roleName&gt;" (e.g. "ROLE:DEVIL_ADVOCATE").
     */
    public record DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
            boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence,
            boolean allowAbstention, VoteConfig voteConfig, PhaseSkipCondition skipIf) {

        /**
         * Convenience constructor with defaults: participants=ALL,
         * turnOrder=SEQUENTIAL, contextScope=FULL, no peer targeting, no custom
         * template, 1 repeat, no approval required.
         */
        public DiscussionPhase(String name, PhaseType type) {
            this(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        }

        /**
         * Backward-compatible constructor without requiresApproval.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, false);
        }

        /**
         * Backward-compatible constructor without convergence (I2) — {@code null} means
         * convergence detection is off, which is the default for every phase that
         * predates I2 and for every style preset (the plan's compat rule: no preset
         * changes; DELPHI's recommended convergence config is documented rather than
         * baked in).
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, null);
        }

        /**
         * Backward-compatible constructor without {@code allowAbstention} (I4) —
         * {@code false} means every participant must produce a contribution, which is
         * the behavior of every phase that predates I4 and of every style preset.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, convergence, false);
        }

        /**
         * Backward-compatible constructor without {@code voteConfig} (I14) or
         * {@code skipIf} (I11) — {@code null}s mean a VOTE phase runs with
         * {@link VoteConfig}'s defaults and the phase always runs, the behavior of
         * every phase that predates those items.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence,
                boolean allowAbstention) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, convergence,
                    allowAbstention, null, null);
        }

        /**
         * I14-shaped constructor without {@code skipIf} — what every vote-phase call
         * site written before the I11 merge uses.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence,
                boolean allowAbstention, VoteConfig voteConfig) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, convergence,
                    allowAbstention, voteConfig, null);
        }

        /**
         * I11-shaped constructor without {@code voteConfig} — what every
         * negotiation-phase call site written before the I14 merge uses.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence,
                boolean allowAbstention, PhaseSkipCondition skipIf) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, convergence,
                    allowAbstention, null, skipIf);
        }
    }

    /**
     * Ballot rules for a {@link PhaseType#VOTE} phase (I14).
     * <p>
     * LLM ballots are <b>correlated</b> — shared priors, sycophancy — so the
     * durable value of a vote is the auditable process artifact (tally, raw
     * ballots, losing-side dissents), not the epistemics. Independence is
     * engineered structurally: save-time validation forces VOTE phases to
     * {@code PARALLEL} + {@code ContextScope.NONE}, so ballots are cast blind
     * against the pre-fan-out transcript snapshot — commit-reveal for LLM purposes,
     * not an instruction the model could ignore.
     *
     * @param method
     *            {@code MAJORITY} — one option per ballot, highest weighted count
     *            wins; {@code APPROVAL} — a ballot may approve several options.
     *            Default MAJORITY
     * @param optionsSource
     *            where the ballot options come from. {@code EXPLICIT} (the reliable
     *            path) takes {@code options} verbatim; {@code LAST_SYNTHESIS}
     *            extracts {@code Option A: …} lines from the latest SYNTHESIS entry
     *            — instruct the synthesis to emit that shape. Default
     *            LAST_SYNTHESIS
     * @param options
     *            the explicit option texts, required (≥ 2) for EXPLICIT
     * @param quorum
     *            the fraction of participants that must cast a valid ballot for the
     *            vote to decide, in (0, 1]. Abstentions and unparseable replies
     *            count toward the denominator only — a mostly-silent team has NOT
     *            reached quorum, and that is signal. Out-of-range values fall back
     *            to the default 0.5
     * @param weights
     *            per-agentId ballot weights, default 1.0 each. Negative weights are
     *            rejected at save time
     * @param weightByConfidence
     *            multiply each ballot by its self-reported 0..1 confidence
     *            (ReConcile-style). Default off — self-reported confidence is
     *            exactly as correlated as the ballots themselves; treat the
     *            weighted tally as process record, not probability
     * @param tiePolicy
     *            what resolves a tie or a quorum failure: {@code MODERATOR_DECIDES}
     *            (one moderator turn choosing among the tied options),
     *            {@code HUMAN_DECIDES} (reserved for I6 human members — rejected at
     *            save time until that ships), or {@code NO_DECISION} (default:
     *            record {@code type=NONE} and carry on)
     */
    public record VoteConfig(VoteMethod method, OptionsSource optionsSource, List<String> options, double quorum,
            Map<String, Double> weights, boolean weightByConfidence, TiePolicy tiePolicy) {

        public static final double DEFAULT_QUORUM = 0.5;

        /** Same normalization choke point as {@link GroupTaskConfig}. */
        public VoteConfig {
            method = method == null ? VoteMethod.MAJORITY : method;
            optionsSource = optionsSource == null ? OptionsSource.LAST_SYNTHESIS : optionsSource;
            options = options == null ? List.of() : List.copyOf(options);
            if (quorum <= 0.0 || quorum > 1.0) {
                quorum = DEFAULT_QUORUM;
            }
            weights = weights == null ? Map.of() : Map.copyOf(weights);
            tiePolicy = tiePolicy == null ? TiePolicy.NO_DECISION : tiePolicy;
        }

        /**
         * All defaults: MAJORITY, LAST_SYNTHESIS, quorum 0.5, unweighted, NO_DECISION.
         */
        public VoteConfig() {
            this(VoteMethod.MAJORITY, OptionsSource.LAST_SYNTHESIS, List.of(), DEFAULT_QUORUM, Map.of(), false, TiePolicy.NO_DECISION);
        }
    }

    /** How a {@link VoteConfig} counts ballots (I14). */
    public enum VoteMethod {
        MAJORITY, APPROVAL
    }

    /** Where a {@link VoteConfig}'s ballot options come from (I14). */
    public enum OptionsSource {
        LAST_SYNTHESIS, EXPLICIT
    }

    /** What resolves a tied or quorum-failed vote (I14). */
    public enum TiePolicy {
        MODERATOR_DECIDES, HUMAN_DECIDES, NO_DECISION
    }

    /**
     * Early-exit detection for a phase whose {@code repeats > 1} (I2). Without this
     * a DELPHI-style phase always burns exactly {@code repeats} rounds, even once
     * the members have stopped changing their positions — "convergence" is prompt
     * text, not behavior.
     * <p>
     * Two mechanisms feed one exit path. <b>Deterministic:</b> every participant
     * abstained this repeat — free, no LLM call, and the reason {@code minRepeats}
     * does not gate it: unanimous silence is evidence on its own terms, not a
     * similarity estimate that needs a baseline. Requires a phase with
     * {@code allowAbstention} (I4), which is what produces the {@code ABSTAINED}
     * entries it counts. <b>Semantic:</b> a judge compares this repeat's
     * contributions with the previous repeat's and returns an agreement score. The
     * judge cannot run before repeat index {@code minRepeats - 1} because it needs
     * a previous repeat to compare against.
     *
     * @param enabled
     *            off by default — an LLM judge costs a call per repeat, so this is
     *            opt-in per phase
     * @param minRepeats
     *            the judge is skipped until this many repeats have completed
     *            (default 2: one round establishes positions, the second is the
     *            first that can differ from it). Values below 2 are raised to 2 —
     *            there is nothing to compare a first repeat against
     * @param threshold
     *            agreement score at or above which the phase is converged (default
     *            0.8). Compared with {@code >=}, so a judge returning exactly the
     *            threshold converges
     * @param judge
     *            {@code "MODERATOR"} (default) runs the group's configured
     *            moderator agent as the judge. {@code "SERVICE"} is accepted and
     *            documented but currently falls back to MODERATOR with a warning:
     *            the shared {@code SummarizationService} needs LLM provider/model
     *            coordinates and credentials that {@code AgentGroupConfiguration}
     *            does not carry, so wiring it needs config this item does not add
     */
    public record ConvergenceConfig(boolean enabled, int minRepeats, double threshold, String judge) {

        /**
         * The lowest {@code minRepeats} that can mean anything — see the record
         * Javadoc.
         */
        public static final int MIN_COMPARABLE_REPEATS = 2;
        public static final double DEFAULT_THRESHOLD = 0.8;
        public static final String JUDGE_MODERATOR = "MODERATOR";
        public static final String JUDGE_SERVICE = "SERVICE";

        /**
         * Normalizes at the one choke point every reader passes through, so no consumer
         * has to re-derive defaults from a partially-specified config (a JSON config
         * naming only {@code enabled} is the common case).
         */
        public ConvergenceConfig {
            minRepeats = Math.max(minRepeats, MIN_COMPARABLE_REPEATS);
            if (threshold <= 0.0 || threshold > 1.0) {
                threshold = DEFAULT_THRESHOLD;
            }
            judge = judge == null || judge.isBlank() ? JUDGE_MODERATOR : judge.trim().toUpperCase();
        }

        /** Convenience: enabled with every other setting defaulted. */
        public ConvergenceConfig(boolean enabled) {
            this(enabled, MIN_COMPARABLE_REPEATS, DEFAULT_THRESHOLD, JUDGE_MODERATOR);
        }
    }

    public enum PhaseType {
        OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUE, REBUTTAL, SYNTHESIS,
        /** Task decomposition and assignment. */
        PLAN,
        /** Task execution by assigned agents. */
        EXECUTE,
        /** Verification of task results. */
        VERIFY,
        /**
         * Explicit ballots (I14). Save-time validation forces VOTE phases to
         * {@code PARALLEL} + {@code ContextScope.NONE} — ballot independence is
         * enforced structurally (the pre-fan-out snapshot plus the F4 peer-visibility
         * matrix mean no ballot can see another cast this phase), not advised in a
         * prompt.
         */
        VOTE,
        /**
         * An opening offer in a negotiation (I11) — the whole turn is the proposal's
         * terms.
         */
        PROPOSAL,
        /**
         * A bargaining turn (I11): accept an open proposal, counter with a new one,
         * and/or record concessions — a typed JSON contract, parsed by
         * {@code NegotiationEngine}.
         */
        BARGAIN,
        /**
         * Post-discussion retrospective (I8): what worked, what failed, what to do
         * differently. Parsed lessons land in team-owned group memory (synthetic owner
         * {@code "group:"+groupId}) so they surface as {@code {properties.*}} in every
         * member's later discussions — institutional knowledge that compounds
         * run-over-run.
         */
        RETRO
    }

    /**
     * The one phase-skip condition (I11) — deliberately a single enum, not an
     * expression language: deterministic governance means a phase is skipped for a
     * reason the engine can PROVE, not one an expression evaluates to.
     */
    public enum PhaseSkipCondition {
        /**
         * Skip when the discussion already carries an AGREEMENT decision — the
         * arbitration phase of a negotiation is only needed when bargaining failed.
         */
        AGREEMENT_REACHED
    }

    public enum TurnOrder {
        SEQUENTIAL, PARALLEL
    }

    /**
     * Controls what prior transcript context each agent receives during a phase.
     */
    public enum ContextScope {
        /** Agent sees only the question (independent thinking). */
        NONE,
        /** Agent sees entire transcript so far. */
        FULL,
        /**
         * Agent sees the immediately preceding phase and the current one.
         * <p>
         * The current phase is included deliberately, and the doc used to omit it: in a
         * sequential phase the second speaker's whole purpose is to react to the first,
         * and excluding the running phase would leave every speaker after the first
         * talking past their peers.
         */
        LAST_PHASE,
        /** Agent sees content from prior phases but not who said it. */
        ANONYMOUS,
        /** Agent sees only entries targeted AT them (for REVISION phase). */
        OWN_FEEDBACK,
        /** Agent sees only its assigned task description. */
        TASK_ONLY,
        /** Agent sees its task plus results of dependency tasks. */
        TASK_WITH_DEPS
    }

    // --- Protocol (error handling / timeouts) ---

    /**
     * Protocol-level configuration for group discussions: timeouts, retries,
     * failure policies, and safety caps.
     *
     * @param agentTimeoutSeconds
     *            per-agent timeout in seconds. Non-positive, or no {@code protocol}
     *            block at all, falls back to {@link #DEFAULT_AGENT_TIMEOUT_SECONDS}
     *            (180)
     * @param onAgentFailure
     *            policy when an agent fails (SKIP, RETRY, ABORT)
     * @param maxRetries
     *            max retry attempts per agent (default: 2)
     * @param onMemberUnavailable
     *            policy when a member is unavailable (SKIP, FAIL)
     * @param maxTurns
     *            global hard cap on total agent turns across all phases. Prevents
     *            runaway discussions from misconfigured rounds. 0 or negative = use
     *            default (50). When exceeded, remaining phases are skipped and
     *            synthesis proceeds with whatever transcript exists.
     * @param maxCostPerDiscussion
     *            dollar ceiling on {@code GroupConversation#getTotalCost()} (I1).
     *            {@code null} = unlimited. Checked before each turn and before each
     *            {@code TaskForceEngine} EXECUTE wave — an already-dispatched,
     *            in-flight turn may still push the total past the ceiling; that
     *            overshoot is accepted, not prevented. A non-positive value is
     *            treated as {@code null} (unlimited) at save time, with a warning —
     *            see {@code AgentGroupStore}.
     * @param onCostExceeded
     *            what to do once {@code maxCostPerDiscussion} is hit. {@code null}
     *            defaults to {@code SYNTHESIZE_NOW} (see the constructor).
     */
    public record ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
            MemberUnavailablePolicy onMemberUnavailable, int maxTurns, Double maxCostPerDiscussion, CostPolicy onCostExceeded) {

        /**
         * Per-agent-turn timeout used when a group configures no {@code protocol}
         * block, and when one supplies a non-positive {@code agentTimeoutSeconds}. 180s
         * covers thinking models (e.g. claude-sonnet-5) and synthesis phases
         * comfortably.
         * <p>
         * Lives here rather than only on the engine so every writer of a default
         * protocol agrees: the MCP {@code create_group} tool used to hard-code 60 while
         * the engine documented 180 and the templates shipped 180, which meant the
         * published default was true of exactly one of the three ways a group gets
         * created.
         */
        public static final int DEFAULT_AGENT_TIMEOUT_SECONDS = 180;

        /** Retry attempts per member turn when {@code onAgentFailure=RETRY}. */
        public static final int DEFAULT_MAX_RETRIES = 2;

        /**
         * Canonical constructor — normalizes a {@code null} {@link #onCostExceeded} to
         * {@link CostPolicy#SYNTHESIZE_NOW} so every reader can treat the field as
         * non-null, the same way {@link #maxTurns}'s 0-or-negative-means-default is
         * normalized at the read site rather than pushed onto every caller.
         */
        public ProtocolConfig {
            if (onCostExceeded == null) {
                onCostExceeded = CostPolicy.SYNTHESIZE_NOW;
            }
        }

        /**
         * Backward-compatible constructor — defaults maxTurns to 0 (engine default:
         * 50), cost ceiling to unlimited.
         */
        public ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
                MemberUnavailablePolicy onMemberUnavailable) {
            this(agentTimeoutSeconds, onAgentFailure, maxRetries, onMemberUnavailable, 0);
        }

        /**
         * Backward-compatible constructor — defaults the cost ceiling to unlimited.
         */
        public ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
                MemberUnavailablePolicy onMemberUnavailable, int maxTurns) {
            this(agentTimeoutSeconds, onAgentFailure, maxRetries, onMemberUnavailable, maxTurns, null, null);
        }

        public enum MemberFailurePolicy {
            SKIP, RETRY, ABORT
        }

        public enum MemberUnavailablePolicy {
            SKIP, FAIL
        }

        /**
         * What happens once {@link #maxCostPerDiscussion} is exceeded (I1).
         */
        public enum CostPolicy {
            /**
             * Stop scheduling new turns; jump ahead to the next remaining SYNTHESIS phase,
             * if any.
             */
            SYNTHESIZE_NOW,
            /**
             * Fail the discussion immediately (state FAILED), with an actionable reason.
             */
            ABORT
        }
    }

    // --- Getters/Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<GroupMember> getMembers() {
        return members;
    }

    public void setMembers(List<GroupMember> members) {
        this.members = members;
    }

    public String getModeratorAgentId() {
        return moderatorAgentId;
    }

    public void setModeratorAgentId(String moderatorAgentId) {
        this.moderatorAgentId = moderatorAgentId;
    }

    public DiscussionStyle getStyle() {
        return style;
    }

    public void setStyle(DiscussionStyle style) {
        this.style = style;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public List<DiscussionPhase> getPhases() {
        return phases;
    }

    public void setPhases(List<DiscussionPhase> phases) {
        this.phases = phases;
    }

    public ProtocolConfig getProtocol() {
        return protocol;
    }

    public void setProtocol(ProtocolConfig protocol) {
        this.protocol = protocol;
    }

    public List<TaskDefinition> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskDefinition> tasks) {
        this.tasks = tasks;
    }

    public DynamicAgentConfig getDynamicAgents() {
        return dynamicAgents;
    }

    public void setDynamicAgents(DynamicAgentConfig dynamicAgents) {
        this.dynamicAgents = dynamicAgents;
    }

    public boolean isRecordDissents() {
        return recordDissents;
    }

    public void setRecordDissents(boolean recordDissents) {
        this.recordDissents = recordDissents;
    }

    /**
     * Human-in-the-loop (HITL) configuration for group discussions. Controls
     * approval timeouts, timeout policies, and granularity level.
     *
     * @since 6.0.0
     */
    private HitlConfig hitlConfig;

    public HitlConfig getHitlConfig() {
        return hitlConfig;
    }

    public void setHitlConfig(HitlConfig hitlConfig) {
        this.hitlConfig = hitlConfig;
    }

    // --- Task Definition ---

    /**
     * A pre-configured task for config-driven task orchestration. When tasks are
     * pre-defined here, the PLAN phase is skipped and these tasks are used
     * directly.
     *
     * @param subject
     *            short task title
     * @param description
     *            detailed instructions for the assigned agent
     * @param assignToRole
     *            "ALL", "ROLE:<name>", or specific agentId
     * @param dependsOn
     *            subjects of tasks that must complete first
     * @param priority
     *            0 = highest
     */
    public record TaskDefinition(
            String subject,
            String description,
            String assignToRole,
            List<String> dependsOn,
            int priority,
            AssignmentMode assignmentMode) {

        public TaskDefinition(String subject, String description) {
            this(subject, description, "ALL", List.of(), 0);
        }

        /** Backward-compatible constructor without {@code assignmentMode} (I18). */
        public TaskDefinition(String subject, String description, String assignToRole, List<String> dependsOn, int priority) {
            this(subject, description, assignToRole, dependsOn, priority, null);
        }

        public TaskDefinition {
            Objects.requireNonNull(subject, "Task subject must not be null");
            Objects.requireNonNull(description, "Task description must not be null");
            if (dependsOn == null) {
                dependsOn = List.of();
            }
            if (assignToRole == null) {
                assignToRole = "ALL";
            }
        }
    }

    /**
     * How a task finds its agent (I18, CNP-lite). {@code null} on a task falls back
     * to {@code taskListConfig.assignmentMode()}, and to {@link #ROLE} when that
     * too is unset — every config that predates I18 behaves exactly as before.
     */
    public enum AssignmentMode {
        /** The planner/config assigns by role or round-robin — today's behavior. */
        ROLE,
        /**
         * Contract-Net-lite: eligible members submit blind parallel bids and the
         * highest confidence wins (deterministic tie-break by speaking order; no bids
         * falls back to ROLE — a wave never stalls on an auction).
         */
        BID
    }

    // --- Dynamic Agent Configuration ---

    /**
     * Lifecycle policy for agents created during a discussion.
     */
    public enum LifecyclePolicy {
        EPHEMERAL, KEEP_DEPLOYED, UNDEPLOY_ONLY, AGENT_DECIDES;

        @JsonValue
        public String toJson() {
            return name().toLowerCase().replace('_', '-');
        }

        @JsonCreator
        public static LifecyclePolicy fromJson(String value) {
            if (value == null)
                return EPHEMERAL;
            return valueOf(value.toUpperCase().replace('-', '_'));
        }
    }

    /**
     * Configuration for dynamic agent creation, recruitment, and delegation during
     * group discussions. Controls guardrails, allowed providers/models, and
     * lifecycle policy for dynamically created agents.
     */
    public static class DynamicAgentConfig {
        private boolean enabled;
        private boolean allowCreation;
        private boolean allowRecruitment;
        private boolean allowDelegation = true;

        private int maxCreatedAgentsPerDiscussion = 5;
        private int maxRecruitedAgentsPerDiscussion = 10;
        private int maxDelegationsPerTask = 3;

        /**
         * Maximum delegation hops. Agent A calling B calling C is depth 2; the call
         * that would exceed this depth is refused.
         * <p>
         * Finding F18: {@code converse_with_agent} had no depth bound at all, so an
         * A→B→A cycle recursed until the 60s per-hop watchdog happened to fire — and
         * because a call without a {@code conversationId} starts a FRESH conversation,
         * the busy-guard never broke the loop. Prompt injection in a single user
         * message was enough to start it.
         */
        private int maxDelegationDepth = 3;

        /**
         * Seconds a delegating agent waits for its delegate's turn before giving up
         * (I7). Previously hard-coded to 60 in {@code ConverseWithAgentTool}, which is
         * far too short for a delegate that itself runs tools or a nested discussion,
         * and far too long for a fan-out of quick lookups — the caller blocks a virtual
         * thread and the model waits for the whole budget.
         * <p>
         * Non-positive values fall back to the default rather than meaning "wait
         * forever": an unbounded wait here is how a delegation cycle became a hang
         * before the depth cap existed.
         */
        private int delegationTimeoutSeconds = DEFAULT_DELEGATION_TIMEOUT_SECONDS;

        public static final int DEFAULT_DELEGATION_TIMEOUT_SECONDS = 60;

        public int getDelegationTimeoutSeconds() {
            return delegationTimeoutSeconds > 0 ? delegationTimeoutSeconds : DEFAULT_DELEGATION_TIMEOUT_SECONDS;
        }

        public void setDelegationTimeoutSeconds(int delegationTimeoutSeconds) {
            this.delegationTimeoutSeconds = delegationTimeoutSeconds;
        }

        /**
         * Agent IDs this agent may delegate to via {@code converse_with_agent}.
         * {@code null} or empty means "any deployed agent" (the previous, unrestricted
         * behavior).
         */
        private List<String> allowedDelegationTargets;

        /** Allowed LLM providers for created agents. Null = inherit parent. */
        private List<String> allowedProviders;
        /**
         * Allowed models per provider. Keys are provider names, values are lists of
         * model names. Null = inherit parent model.
         */
        private Map<String, List<String>> allowedModels;
        private boolean inheritParentModel = true;

        /**
         * Lifecycle policy for agents created during the discussion.
         * <ul>
         * <li>{@code ephemeral} — auto-delete after discussion ends</li>
         * <li>{@code keep-deployed} — keep deployed for future use</li>
         * <li>{@code undeploy-only} — undeploy but keep config</li>
         * <li>{@code agent-decides} — default ephemeral, but agent can retain</li>
         * </ul>
         */
        private LifecyclePolicy lifecyclePolicy = LifecyclePolicy.EPHEMERAL;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public boolean isAllowCreation() {
            return allowCreation;
        }
        public void setAllowCreation(boolean allowCreation) {
            this.allowCreation = allowCreation;
        }
        public boolean isAllowRecruitment() {
            return allowRecruitment;
        }
        public void setAllowRecruitment(boolean allowRecruitment) {
            this.allowRecruitment = allowRecruitment;
        }
        public boolean isAllowDelegation() {
            return allowDelegation;
        }
        public void setAllowDelegation(boolean allowDelegation) {
            this.allowDelegation = allowDelegation;
        }
        public int getMaxCreatedAgentsPerDiscussion() {
            return maxCreatedAgentsPerDiscussion;
        }
        public void setMaxCreatedAgentsPerDiscussion(int max) {
            this.maxCreatedAgentsPerDiscussion = max;
        }
        public int getMaxRecruitedAgentsPerDiscussion() {
            return maxRecruitedAgentsPerDiscussion;
        }
        public void setMaxRecruitedAgentsPerDiscussion(int max) {
            this.maxRecruitedAgentsPerDiscussion = max;
        }
        public int getMaxDelegationsPerTask() {
            return maxDelegationsPerTask;
        }
        public void setMaxDelegationsPerTask(int max) {
            this.maxDelegationsPerTask = max;
        }
        public int getMaxDelegationDepth() {
            return maxDelegationDepth;
        }
        public void setMaxDelegationDepth(int maxDelegationDepth) {
            this.maxDelegationDepth = maxDelegationDepth;
        }
        public List<String> getAllowedDelegationTargets() {
            return allowedDelegationTargets;
        }
        public void setAllowedDelegationTargets(List<String> allowedDelegationTargets) {
            this.allowedDelegationTargets = allowedDelegationTargets;
        }
        public List<String> getAllowedProviders() {
            return allowedProviders;
        }
        public void setAllowedProviders(List<String> allowedProviders) {
            this.allowedProviders = allowedProviders;
        }
        public Map<String, List<String>> getAllowedModels() {
            return allowedModels;
        }
        public void setAllowedModels(Map<String, List<String>> allowedModels) {
            this.allowedModels = allowedModels;
        }
        public boolean isInheritParentModel() {
            return inheritParentModel;
        }
        public void setInheritParentModel(boolean inheritParentModel) {
            this.inheritParentModel = inheritParentModel;
        }
        public LifecyclePolicy getLifecyclePolicy() {
            return lifecyclePolicy;
        }
        public void setLifecyclePolicy(LifecyclePolicy lifecyclePolicy) {
            this.lifecyclePolicy = lifecyclePolicy != null ? lifecyclePolicy : LifecyclePolicy.EPHEMERAL;
        }
    }

    /**
     * HITL approval timeout configuration for group discussions. Extends the
     * agent-level config with a {@code granularity} field to control at what level
     * human approval is required: {@code PHASE} (after each gated phase) or
     * {@code TASK} (per task inside a gated EXECUTE phase; non-EXECUTE phases fall
     * back to PHASE behavior).
     *
     * @since 6.0.0
     */
    public static class HitlConfig {
        private String approvalTimeout;
        private HitlTimeoutPolicy timeoutPolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
        private HitlGranularity granularity = HitlGranularity.PHASE;
        private HitlRejectionPolicy onTaskRejection = HitlRejectionPolicy.FAIL;

        public String getApprovalTimeout() {
            return approvalTimeout;
        }

        public void setApprovalTimeout(String approvalTimeout) {
            this.approvalTimeout = approvalTimeout;
        }

        public HitlTimeoutPolicy getTimeoutPolicy() {
            return timeoutPolicy;
        }

        public void setTimeoutPolicy(HitlTimeoutPolicy timeoutPolicy) {
            // Coalesce null to the default (mirrors setLifecyclePolicy) so an explicit
            // JSON "timeoutPolicy": null cannot silently disable the default policy.
            this.timeoutPolicy = timeoutPolicy != null ? timeoutPolicy : HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }

        public HitlGranularity getGranularity() {
            return granularity;
        }

        public void setGranularity(HitlGranularity granularity) {
            this.granularity = granularity != null ? granularity : HitlGranularity.PHASE;
        }

        public HitlRejectionPolicy getOnTaskRejection() {
            return onTaskRejection;
        }

        public void setOnTaskRejection(HitlRejectionPolicy onTaskRejection) {
            this.onTaskRejection = onTaskRejection != null ? onTaskRejection : HitlRejectionPolicy.FAIL;
        }
    }

}
