/* ──────────────────────────────────────────────
   Group discussions: the contract a team conversation runs on — the group
   configuration's shape, the stored discussion (`GroupConversation`, its
   transcript and decision), the SSE events of a live run, and the display
   fallbacks for styles and entry types. Types, constants and pure helpers only;
   the calls themselves stay with each app's authenticated client
   (`@eddi/app-kit/lib/api/groups`, which re-exports this module). Moved
   here from that file in phase 6 of the frontend unification plan, so
   `@eddi/conversation`'s GroupDiscussion controller and team components can
   use it without importing an app's code.
   ────────────────────────────────────────────── */

import type { ApprovalContextView, HitlGranularity, HitlTimeoutPolicy } from "./hitl";

// ─── Enums & Types ───────────────────────────────────────────────

export const DISCUSSION_STYLES = [
  "ROUND_TABLE",
  "PEER_REVIEW",
  "DEVIL_ADVOCATE",
  "DELPHI",
  "DEBATE",
  "TASK_FORCE",
  /** Contract-Net-lite bargaining between named parties, arbitrated on failure (I11). */
  "NEGOTIATION",
  "CUSTOM",
] as const;
export type DiscussionStyle = (typeof DISCUSSION_STYLES)[number];

export const PHASE_TYPES = [
  "OPINION",
  "CRITIQUE",
  "REVISION",
  "CHALLENGE",
  "DEFENSE",
  "ARGUE",
  "REBUTTAL",
  "SYNTHESIS",
  "PLAN",
  "EXECUTE",
  "VERIFY",
  /** A ballot phase (I14) — requires turnOrder PARALLEL + contextScope NONE at save time. */
  "VOTE",
  /** A negotiation offer phase (I11). */
  "PROPOSAL",
  /** A negotiation counter-offer/concession phase (I11). */
  "BARGAIN",
  /** Post-discussion retrospective (I8) — harvests lessons into group memory. */
  "RETRO",
] as const;
export type PhaseType = (typeof PHASE_TYPES)[number];

export type TurnOrder = "SEQUENTIAL" | "PARALLEL";

export type ContextScope =
  | "NONE"
  | "FULL"
  | "LAST_PHASE"
  | "ANONYMOUS"
  | "OWN_FEEDBACK"
  | "TASK_ONLY"
  | "TASK_WITH_DEPS";

export type MemberType = "AGENT" | "GROUP" | "HUMAN";

export type MemberFailurePolicy = "SKIP" | "RETRY" | "ABORT";
export type MemberUnavailablePolicy = "SKIP" | "FAIL";

/**
 * What a missing `ProtocolConfig.onAgentFailure` / `onMemberUnavailable` means.
 *
 * Both are non-null on the backend record, but its JSON simply leaves out
 * whatever the stored document never carried — a group created straight over the
 * API rather than through the wizard arrives without them. Everything on this
 * side treats them as set: the settings editor merges these same defaults in
 * before binding its `<select>`s, and the config panel formats them for display.
 *
 * SKIP is the backend's own behaviour for an absent value, not an assumption
 * from what this UI happens to write. `ProtocolConfig`'s canonical constructor
 * normalises only `onCostExceeded`, so Jackson leaves these two `null` — and
 * every read of them is an equality test against a NON-skip value:
 * `onAgentFailure() == RETRY` and `== ABORT` (`MemberTurnExecutor`,
 * `TaskForceEngine`, `GroupConversationService`), `onMemberUnavailable() == FAIL`
 * (`MemberTurnExecutor`). A null takes none of those branches, which is exactly
 * what SKIP does. `GroupConversationService.resolveProtocol` substitutes the same
 * pair when the protocol block is absent entirely.
 *
 * That equivalence is why filling it here is safe even though the partial
 * editors (advanced, phases, HITL) save the whole normalised config back: the
 * value written is the one the engine was already applying.
 */
export const DEFAULT_MEMBER_FAILURE_POLICY: MemberFailurePolicy = "SKIP";
export const DEFAULT_MEMBER_UNAVAILABLE_POLICY: MemberUnavailablePolicy = "SKIP";

/**
 * What happens once `ProtocolConfig.maxCostPerDiscussion` is exceeded (EDDI I1).
 * The backend's canonical constructor coalesces a null to SYNTHESIZE_NOW, so
 * every reader may treat the field as set once a ceiling exists.
 */
export type CostPolicy = "SYNTHESIZE_NOW" | "ABORT";

/** Backend `AgentGroupConfiguration.MAX_MEMBERS` — every member is one LLM call per phase. */
export const MAX_GROUP_MEMBERS = 100;

/**
 * Backend `AgentGroupConfiguration.MAX_DISCUSSION_ROUNDS`. `maxRounds` multiplies
 * into concrete phases for ROUND_TABLE and DELPHI and every phase fans out to
 * every member, so this value alone multiplies the cost of one discussion.
 */
export const MAX_DISCUSSION_ROUNDS = 50;

/**
 * Every value of the backend's `GroupConversation.GroupConversationState`.
 *
 * A runtime array rather than a bare union, and the union is derived from it, so
 * the two cannot drift. Several render sites key translations off the state name
 * with a template literal — ``t(`groups.state.${state}`)`` — which `npm run
 * i18n:check` cannot see, so a state added to a union alone shipped with no
 * translation in any of the eleven locales and every gate stayed green.
 * `i18n-quality.test.ts` walks this array.
 */
export const GROUP_CONVERSATION_STATES = [
  "CREATED",
  "IN_PROGRESS",
  "SYNTHESIZING",
  "COMPLETED",
  "FAILED",
  /**
   * A human rejected the discussion's recommendation at a HITL gate.
   *
   * Deliberately not FAILED — the run did not break, a person declined its
   * result. Terminal and closeable exactly as FAILED is; only the meaning
   * differs, and rendering a recorded decision as a red "Failed" badge told
   * operators the system had gone wrong when it had done what it was asked.
   * Documents written before the backend had this state carry FAILED for a
   * rejection and are left alone.
   */
  "REJECTED",
  "CANCELLED",
  "AWAITING_APPROVAL",
  /**
   * A HUMAN group member's turn is up (I6): the discussion is parked on
   * `pendingHumanInput` until that member submits via `submitHumanInput`, or the
   * group's `humanMemberConfig` timeout policy resolves the turn. Distinct from
   * AWAITING_APPROVAL — this is "you're up", not "approve/reject".
   */
  "AWAITING_HUMAN_INPUT",
  // Terminal — member conversations ended, ephemeral agents cleaned up, no
  // further follow-ups/continuations (backend GroupConversationState.CLOSED).
  "CLOSED",
] as const;

export type GroupConversationState = (typeof GROUP_CONVERSATION_STATES)[number];

/**
 * Post-COMPLETED lifecycle operations the backend exposes on a group
 * conversation. Mirrors the identifiers returned by the backend's computed
 * `availableActions` field (GroupConversation.getAvailableActions):
 *   - COMPLETED                       → ["followup", "continue", "close"]
 *   - FAILED / REJECTED / CANCELLED   → ["close"]
 *   - AWAITING_HUMAN_INPUT  → ["submitHumanInput"]
 *   - all other states      → []
 */
export type GroupConversationAction =
  | "followup"
  | "continue"
  | "close"
  | "submitHumanInput";

/**
 * Mirrors the backend `GroupConversation.TranscriptEntryType` in full. The last
 * eleven were declared ahead of their producers as part of the Wave 0 (F4) work;
 * all now have real producers (VOTE/I14, PROPOSAL+BARGAIN/I11, RETRO/I8, BID/I18)
 * — except HUMAN_INPUT, which stays declared-but-dead: a HUMAN member's turn is
 * recorded under the phase's own natural entry type (or FOLLOW_UP for a
 * facilitator ESCALATE_HUMAN pause), never this value. Still declared because a
 * transcript is rendered by type and an unmodelled value is exactly what used to
 * blank the view — see `entryTypeInfo`.
 */
export type TranscriptEntryType =
  | "QUESTION"
  | "OPINION"
  | "CRITIQUE"
  | "REVISION"
  | "CHALLENGE"
  | "DEFENSE"
  | "ARGUMENT"
  | "REBUTTAL"
  | "SYNTHESIS"
  | "ERROR"
  | "SKIPPED"
  | "PLAN"
  | "TASK_RESULT"
  | "VERIFICATION"
  /** User-to-member or member-to-user follow-up exchange between rounds. */
  | "FOLLOW_UP"
  /** A speaker declined to add anything new this round (I4). Peer-hidden. */
  | "ABSTAINED"
  /** A member's recorded disagreement with a synthesis (I4). Peer-visible. */
  | "DISSENT"
  /** A convergence judge's agreement-score result (I2). Peer-hidden. */
  | "CONVERGENCE"
  /** A facilitator's bounded intervention, e.g. a recruitment (I12/I7). Peer-hidden. */
  | "FACILITATION"
  /** A cast ballot (I14). */
  | "VOTE"
  /** A negotiation offer (I11). */
  | "PROPOSAL"
  /** A negotiation counter-offer or concession (I11). */
  | "BARGAIN"
  /** A human group member's contribution (I6). */
  | "HUMAN_INPUT"
  /** Retrospective phase output, feeding group memory (I8). */
  | "RETRO"
  /** A bid for a task assignment (I18). */
  | "BID";

// ─── Data Models ─────────────────────────────────────────────────

export interface GroupMember {
  agentId: string;
  displayName: string;
  speakingOrder: number | null;
  role: string | null;
  memberType?: MemberType;
}

/**
 * Early-exit detection for a phase whose `repeats > 1` (EDDI I2). Without it a
 * DELPHI-style phase burns exactly `repeats` rounds even once the members have
 * stopped changing their positions.
 *
 * The backend's compact constructor normalises on read, so a partially-specified
 * object is legal: `minRepeats` below 2 is raised to 2 (there is nothing to
 * compare a first repeat against), a `threshold` outside (0,1] falls back to 0.8,
 * and a blank `judge` becomes MODERATOR.
 */
export interface ConvergenceConfig {
  /** Off by default — an LLM judge costs a call per repeat. */
  enabled: boolean;
  /** The judge is skipped until this many repeats have completed (default 2, floor 2). */
  minRepeats: number;
  /** Agreement score at or above which the phase is converged (default 0.8, compared with >=). */
  threshold: number;
  /**
   * `"MODERATOR"` (default) runs the group's moderator as judge. `"SERVICE"`
   * calls the model named in `service` directly (no moderator needed); without a
   * `service` model it falls back to the moderator with a backend warning.
   */
  judge: ConvergenceJudge;
  /** The model `judge: "SERVICE"` calls. Ignored for MODERATOR. */
  service?: ServiceJudgeConfig | null;
  /**
   * What the judge decides (E2). `"SIMILARITY"` (default, absent on configs saved
   * before 6.6) scores agreement with the previous repeat against `threshold`.
   * `"VERDICT"` reviews the latest repeat against the question and ends the phase
   * once it passes; `threshold` is unused and `minRepeats` may be 1.
   */
  mode?: ConvergenceMode;
}

export type ConvergenceJudge = "MODERATOR" | "SERVICE";

/**
 * Backend `ServiceJudgeConfig`: the model a SERVICE convergence judge calls. A
 * credential-named parameter (`apiKey`, ...) must be a `${vault:...}` or
 * `${vars:...}` reference — a literal is refused at save time.
 */
export interface ServiceJudgeConfig {
  llmProvider?: string | null;
  llmModel?: string | null;
  parameters?: Record<string, string> | null;
  /** USD per 1M input tokens; absent = unpriced ($0). */
  inputPricePer1M?: number | null;
  /** USD per 1M output tokens; absent = unpriced ($0). */
  outputPricePer1M?: number | null;
  /** Per-call timeout in ms (default 20000, ceiling 60000). */
  timeoutMs?: number | null;
}

export type ConvergenceMode = "SIMILARITY" | "VERDICT";

/** Backend `AgentGroupConfiguration.MAX_REWORKS_LIMIT`: the save-time bound of `maxReworks`. */
export const MAX_REWORKS = 5;

/** `VoteConfig.method` (I14). No SUPERMAJORITY exists — only these two. */
export type VoteMethod = "MAJORITY" | "APPROVAL";

/**
 * Where a VOTE phase's ballot options come from (I14). `LAST_SYNTHESIS` is the
 * backend default — options are extracted from the most recent SYNTHESIS output
 * rather than hand-typed, since a decision-board's "Options" phase is usually
 * itself LLM-authored.
 */
export type VoteOptionsSource = "LAST_SYNTHESIS" | "EXPLICIT";

/**
 * How a tied ballot resolves (I14). `HUMAN_DECIDES` is a real enum value but is
 * still save-time REJECTED by the backend pending its resume machinery — despite
 * I6 (human members) having since shipped, that cross-reference in the backend's
 * own docs is stale. Treat it as unselectable, matching backend reality, not the
 * aspirational doc.
 */
export type VoteTiePolicy = "MODERATOR_DECIDES" | "HUMAN_DECIDES" | "NO_DECISION";

/** Backend `AgentGroupConfiguration.VoteConfig.DEFAULT_QUORUM`. Valid range (0,1]. */
export const DEFAULT_VOTE_QUORUM = 0.5;

/**
 * Ballot configuration for a VOTE-type phase (I14). The backend hard-rejects a
 * VOTE phase at save time unless `turnOrder="PARALLEL"` and
 * `contextScope="NONE"` — a ballot cast is blind, matching BID's commit-reveal
 * discipline.
 */
export interface VoteConfig {
  method: VoteMethod;
  optionsSource: VoteOptionsSource;
  /** Only read when `optionsSource === "EXPLICIT"`. */
  options: string[];
  /** (0,1]. Backend default 0.5 when unset/non-positive. */
  quorum: number;
  /** agentId → weight, for a weighted tally. Empty = every ballot counts equally. */
  weights: Record<string, number>;
  /** Scale each ballot's weight by the voter's stated confidence, if parsed. */
  weightByConfidence: boolean;
  tiePolicy: VoteTiePolicy;
}

/**
 * Early-exit condition for a phase, skipped entirely when its condition already
 * holds (I11's Arbitration phase, e.g., is a SYNTHESIS phase with
 * `skipIf: "AGREEMENT_REACHED"` — it only runs when bargaining failed to reach
 * one). Deliberately a single value today; `null`/absent means never skip.
 */
export type PhaseSkipCondition = "AGREEMENT_REACHED";

export interface DiscussionPhase {
  name: string;
  type: PhaseType;
  participants: string;
  turnOrder: TurnOrder;
  contextScope: ContextScope;
  targetEachPeer: boolean;
  inputTemplate: string | null;
  repeats: number;
  requiresApproval?: boolean;
  /** Convergence-based early exit (I2). `null`/absent = off. Only acts when `repeats > 1`. */
  convergence?: ConvergenceConfig | null;
  /**
   * Let a participant decline to add anything new this round (I4), recorded as an
   * ABSTAINED entry. Also what feeds convergence's deterministic
   * everybody-abstained exit.
   */
  allowAbstention?: boolean;
  /**
   * Ballot configuration for a `type: "VOTE"` phase (I14). Optional even then —
   * `AgentGroupStore.validateVotePhases` skips a null one and the engine falls
   * back to its own defaults — but the editor writes an explicit block so the
   * saved document says what will actually run.
   */
  voteConfig?: VoteConfig | null;
  /** Skip this phase entirely when the condition already holds (I11). `null`/absent = never skip. */
  skipIf?: PhaseSkipCondition | null;
}

export interface ProtocolConfig {
  agentTimeoutSeconds: number;
  onAgentFailure: MemberFailurePolicy;
  maxRetries: number;
  onMemberUnavailable: MemberUnavailablePolicy;
  maxTurns?: number;
  /**
   * Dollar ceiling on the discussion's accumulated cost (I1). `null` = unlimited.
   * Checked before each turn and each TASK_FORCE EXECUTE wave, so an already
   * in-flight turn may still push the total past it.
   *
   * A non-positive value is coerced to `null` (unlimited) by `AgentGroupStore` at
   * save time — the UI refuses it up front rather than letting a save silently
   * mean the opposite of what was typed.
   */
  maxCostPerDiscussion?: number | null;
  /** What to do once the ceiling is hit. Absent defaults to SYNTHESIZE_NOW. */
  onCostExceeded?: CostPolicy | null;
}

/**
 * How a TASK_FORCE task is assigned to a member (I18).
 *
 * `ROLE` is the original behaviour: the planner or config assigns by role, or
 * round-robin. `BID` is Contract-Net-lite — eligible members submit blind,
 * parallel bids and the highest confidence wins, tie-broken deterministically by
 * speaking order and then agent id so every pod resolves a tie identically.
 *
 * A BID wave silently falls back to ROLE when the auction cannot pay for itself
 * — fewer than two eligible bidders or fewer than two unassigned tasks
 * (`TaskBidEngine.auctionWorthwhile`), when the remaining turn budget could not
 * cover one turn per bidder, or when nobody bid on a given task.
 */
export type AssignmentMode = "ROLE" | "BID";

/**
 * Whether and how far members may file their own tasks mid-discussion (EDDI I5).
 * Absent means the `addGroupTask`/`listGroupTasks` tools are not assembled at all
 * — an absent tool costs no prompt tokens and cannot be argued with, which is why
 * the backend prefers absence to a tool that always refuses.
 */
export interface GroupTaskConfig {
  /** Master switch. Off = the tools do not exist for this group. */
  allowAgentTaskCreation: boolean;
  /** Ceiling across the whole discussion (backend default 20; non-positive → default). */
  maxAgentAddedTasksPerDiscussion: number;
  /** Ceiling within one member turn (backend default 3; non-positive → default). */
  maxPerTurn: number;
  /**
   * Group-wide default assignment mode (I18). A per-task `TaskDefinition.assignmentMode`
   * overrides this for pre-configured tasks; LLM-planned tasks always use this
   * default (they have no backing TaskDefinition). Backend normalizes a missing
   * value to `"ROLE"` at construction — never actually `null`/absent on a saved
   * config, but treat it as optional on the way in regardless.
   */
  assignmentMode?: AssignmentMode;
}

export const DEFAULT_MAX_AGENT_TASKS_PER_DISCUSSION = 20;
export const DEFAULT_MAX_AGENT_TASKS_PER_TURN = 3;

// ─── Task Models ────────────────────────────────────────────────

export type TaskStatus =
  | "PENDING"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "VERIFIED"
  | "FAILED"
  | "BLOCKED"
  | "AWAITING_APPROVAL";

export interface TaskItem {
  id: string;
  subject: string;
  description: string;
  status: TaskStatus;
  assignedAgentId: string | null;
  assignedDisplayName: string | null;
  dependsOnIds: string[];
  result: string | null;
  verificationNote: string | null;
  verified: boolean;
  priority: number;
  createdAt: string;
  completedAt: string | null;
  /**
   * The member that filed this task via `addGroupTask` (I5). `null` for tasks
   * authored by config or by the PLAN phase — which is what every task was before
   * agent-filed tasks existed.
   */
  createdByAgentId?: string | null;
}

/**
 * The winning bid a BID-mode task was awarded on (I18) — per-task scheduling
 * metadata, not a discussion-wide `DecisionRecord`. `confidence` is the bidder's
 * self-assessed 0..1 score (clamped at parse), `estimatedComplexity` their own
 * XS/S/M/L estimate verbatim.
 */
export interface AwardedBid {
  agentId: string;
  confidence: number;
  estimatedComplexity: string;
  rationale: string;
}

export interface SharedTaskList {
  tasks: TaskItem[];
  /**
   * taskId → the bid it was awarded on (I18). Only present for BID-mode tasks
   * that received at least one bid — a BID-mode task nobody bid on falls back to
   * ROLE assignment and is indistinguishable here from a task configured as ROLE
   * from the start (cross-reference `AgentGroupConfiguration.tasks[].assignmentMode`
   * for pre-configured tasks if that distinction matters).
   */
  awardedBids?: Record<string, AwardedBid>;
}

export interface TaskDefinition {
  subject: string;
  description: string;
  assignToRole: string;
  dependsOn: string[] | null;
  priority: number;
  /** Per-task override of the group's `taskListConfig.assignmentMode` (I18). `null`/absent defers to the group default. */
  assignmentMode?: AssignmentMode | null;
}

export const LIFECYCLE_POLICIES = [
  "EPHEMERAL",
  "KEEP_DEPLOYED",
  "UNDEPLOY_ONLY",
  "AGENT_DECIDES",
] as const;
export type LifecyclePolicy = (typeof LIFECYCLE_POLICIES)[number];

/**
 * `AgentGroupConfiguration.LifecyclePolicy` is the one group enum carrying
 * Jackson's `@JsonValue`, so the backend *writes* `"ephemeral"`,
 * `"keep-deployed"`, `"undeploy-only"`, `"agent-decides"` — lower-case, hyphenated
 * — while its `@JsonCreator` *reads* either form. Everything on this side speaks
 * the canonical constant, and every config that arrives is normalised through
 * here on the way in (`normalizeGroupConfig`).
 *
 * Without this a stored `"ephemeral"` matched no `<option>` in the settings
 * editor, so the control showed the wrong policy and saving it wrote a value the
 * user never chose.
 */
export function normalizeLifecyclePolicy(
  value: string | null | undefined,
): LifecyclePolicy {
  if (!value) return "EPHEMERAL";
  const canonical = value.trim().toUpperCase().replace(/-/g, "_");
  return (LIFECYCLE_POLICIES as readonly string[]).includes(canonical)
    ? (canonical as LifecyclePolicy)
    : "EPHEMERAL";
}

export interface DynamicAgentConfig {
  enabled: boolean;
  allowCreation: boolean;
  allowRecruitment: boolean;
  allowDelegation: boolean;
  maxCreatedAgentsPerDiscussion: number;
  maxRecruitedAgentsPerDiscussion: number;
  maxDelegationsPerTask: number;
  /**
   * Maximum delegation hops (backend default 3). A→B→C is depth 2; the call that
   * would exceed this is refused. Without a bound an A→B→A cycle recursed until a
   * per-hop watchdog happened to fire.
   */
  maxDelegationDepth?: number;
  /**
   * Seconds a delegating agent waits for its delegate's turn (backend default 60).
   * Non-positive falls back to the default rather than meaning "wait forever".
   */
  delegationTimeoutSeconds?: number;
  /**
   * Agent IDs this group's members may delegate to. Empty/absent means any
   * deployed agent.
   */
  allowedDelegationTargets?: string[] | null;
  allowedProviders: string[];
  allowedModels: Record<string, string[]>;
  inheritParentModel: boolean;
  lifecyclePolicy: LifecyclePolicy;
}

export const DEFAULT_MAX_DELEGATION_DEPTH = 3;
export const DEFAULT_DELEGATION_TIMEOUT_SECONDS = 60;

/**
 * Bounds for RETRO lessons (I8). Non-positive values fall back to the default;
 * both are additionally hard-ceilinged (20 / 500) by the backend's compact
 * constructor — the UI should mirror those ceilings rather than let a save
 * silently clamp a larger typed value.
 */
export interface RetroConfig {
  maxLessonsPerRun: number;
  maxStoredLessons: number;
}
export const RETRO_DEFAULT_MAX_PER_RUN = 3;
export const RETRO_DEFAULT_MAX_STORED = 50;
export const RETRO_CEILING_MAX_PER_RUN = 20;
export const RETRO_CEILING_MAX_STORED = 500;

/**
 * Transcript windowing (I9): once a FULL/ANONYMOUS-scope render would exceed
 * `maxRecentEntries`, older entries are either summarized (if `summarizeOverflow`
 * and `llmProvider`/`llmModel` are set) or replaced with a plain
 * `"[N earlier entries omitted]"` marker. ANONYMOUS phases keep a SEPARATE
 * rolling summary from FULL phases (`GroupConversation.anonymousTranscriptSummary`)
 * so a summary can never leak real speaker names into an anonymous round.
 */
export interface ContextWindowConfig {
  /** Master switch. `false`/absent = every turn still renders the full scoped transcript. */
  enabled: boolean;
  /** Backend default 30 when non-positive. */
  maxRecentEntries: number;
  /** `null`/absent behaves as `true` (backend coalesces). Only `false` literal disables summarization. */
  summarizeOverflow?: boolean | null;
  /** Required (with llmModel) for summarization to actually run; otherwise falls back to the truncation marker. */
  llmProvider?: string | null;
  llmModel?: string | null;
  /** USD per 1M input/output tokens for the summarizer's own calls (N1-style pricing). `null` = unpriced ($0). */
  inputPricePer1M?: number | null;
  outputPricePer1M?: number | null;
}
export const CONTEXT_WINDOW_DEFAULT_MAX_RECENT_ENTRIES = 30;

/**
 * Governs the overview dashboard's member stances.
 *
 * Deliberately shaped like {@link ContextWindowConfig} — but with NO `enabled`
 * flag. Stances always exist (extraction needs no configuration), so a boolean
 * could only ever have meant "may this spend money?", which is already what
 * naming a provider and model means.
 */
export interface StanceSummaryConfig {
  /** Hard cap on a rendered stance. Backend default 160 when non-positive. */
  maxChars: number;
  /** Both required for the summarizer to run; otherwise every stance is extracted. */
  llmProvider?: string | null;
  llmModel?: string | null;
  /** USD per 1M input/output tokens. `null` = unpriced ($0). */
  inputPricePer1M?: number | null;
  outputPricePer1M?: number | null;
}

/** One member's current position in one line, as persisted on the conversation. */
export interface MemberStance {
  text: string;
  /** How many of that member's own contributions this reflects. */
  coveredContributions: number;
  /** `true` = LLM paraphrase, `false` = the member's own lead sentence. */
  llmGenerated: boolean;
  updated: string;
}

/** The closed set of declarative artifact validators (I17). Declarative only — never arbitrary code. */
export type ArtifactValidatorKind = "JSON_SCHEMA" | "REGEX" | "MAX_LENGTH";

/**
 * One declarative artifact validator (I17): `kind` selects the check, `spec`
 * parameterizes it — a JSON Schema document (JSON_SCHEMA), a pattern the content
 * must match (REGEX), or a max character count (MAX_LENGTH). Validated at save
 * time; a failed write is rejected with the validator's own message and nothing
 * is stored.
 */
export interface ArtifactValidator {
  kind: ArtifactValidatorKind;
  spec: string;
}

/**
 * Governs shared artifacts (I17, blackboard-lite): typed documents members
 * co-edit through tools instead of re-parsing each other's prose. Same
 * opt-in-by-absence discipline as {@link GroupTaskConfig}.
 */
export interface ArtifactConfig {
  allowArtifactTools: boolean;
  maxArtifactsPerDiscussion: number;
  validators: ArtifactValidator[];
}
export const ARTIFACT_DEFAULT_MAX_PER_DISCUSSION = 5;

/** What an expired HUMAN turn does (I6). */
export type OnHumanTimeout = "SKIP_TURN" | "ABORT";

/**
 * Per-HUMAN-member turn policy (I6). `turnTimeout` is an ISO-8601 duration
 * (e.g. `"PT24H"`); `null`/absent waits indefinitely. `onTimeout` defaults to
 * `SKIP_TURN` (records a SKIPPED entry and moves on) when unset.
 */
export interface HumanMemberConfig {
  turnTimeout: string | null;
  onTimeout: OnHumanTimeout;
}

/**
 * The moves a facilitator may choose from (I12). The move list IS the feature
 * surface — each non-CONTINUE move drives machinery another feature already
 * built (I2 phase-exit, I14 vote phases, I7 recruitment, I6 pending human input).
 */
export type FacilitatorMove =
  | "CONTINUE"
  | "END_PHASE"
  | "EXTEND_PHASE"
  | "CALL_VOTE"
  | "RECRUIT"
  | "ESCALATE_HUMAN";

/** When a facilitator checkpoint runs (I12). EACH_REPEAT is required for END_PHASE/EXTEND_PHASE, which act mid-phase. */
export type FacilitatorCheckpoint = "EACH_PHASE" | "EACH_REPEAT";

/**
 * Bounded adaptive orchestration (I12): a facilitator agent is briefed at
 * configured checkpoints and chooses ONE move from a config-enumerated list —
 * never a free-form LLM orchestrator. Every selection is validated; an invalid
 * or missing choice falls back to CONTINUE.
 */
export interface FacilitatorConfig {
  /** Master switch. `false` (default) = no checkpoint ever runs, no extra cost. */
  enabled: boolean;
  /** The agent that plays facilitator — required when enabled. Runs under its own conversation key, never a member's. */
  agentId: string | null;
  /** Backend defaults to `["CONTINUE"]` when empty/absent. */
  allowedMoves: FacilitatorMove[];
  checkAfter: FacilitatorCheckpoint;
  /** Backend default 10 when non-positive. */
  maxMovesPerDiscussion: number;
  /** Principal id ESCALATE_HUMAN pauses for — required at save time when that move is allowed. */
  escalateTo: string | null;
}
export const FACILITATOR_DEFAULT_MAX_MOVES = 10;
/**
 * Hard ceiling, not a suggestion: `AgentGroupStore.validateFacilitator` throws on
 * anything above this — "the facilitator is a bounded intervention mechanism, not
 * an orchestrator". Mirrored here so the editor can block before the round-trip.
 */
export const FACILITATOR_MAX_MOVES_CEILING = 100;

export interface TranscriptEntry {
  speakerAgentId: string;
  speakerDisplayName: string;
  content: string | null;
  phaseIndex: number;
  phaseName: string | null;
  type: TranscriptEntryType;
  timestamp: string;
  errorReason: string | null;
  targetAgentId: string | null;
  /** Base64 Ed25519 signature when the speaker has `signInterAgentMessages`. */
  signature?: string | null;
  /** Replay-protection nonce; null on unsigned entries. */
  signatureNonce?: string | null;
  /** Epoch millis the envelope was signed; null on unsigned entries. */
  signatureTimestampMs?: number | null;
  /** Signing key version. `0` means the pre-versioning legacy `publicKey`. */
  signatureKeyVersion?: number | null;
}

/**
 * `true` when an entry carries full envelope data (signature + nonce + timestamp)
 * and is therefore cryptographically verifiable — the frontend mirror of
 * `TranscriptEntry.hasEnvelopeData()`. A bare `signature` is from an older
 * backend and can be displayed but not verified.
 */
export function hasEnvelopeData(entry: TranscriptEntry): boolean {
  return (
    !!entry.signature &&
    !!entry.signatureNonce &&
    entry.signatureTimestampMs != null
  );
}

/** What kind of conclusion a {@link DecisionRecord} represents (EDDI Wave 0, F3). */
export type DecisionType =
  /** A debate judged to a winner (I3). */
  | "VERDICT"
  /** A tallied ballot (I14). */
  | "VOTE"
  /** A negotiated compromise both sides accepted (I11). */
  | "AGREEMENT"
  /** A task/turn awarded by bid (I18). */
  | "AWARD"
  /** No structured decision was produced — prose-only conclusion. */
  | "NONE";

/** One member's recorded disagreement with a decision (I4/F3). */
export interface Dissent {
  agentId: string;
  displayName: string;
  /** The member's own short statement of where they disagree. */
  position: string;
}

/**
 * Typed outcome of a discussion, or of one decision-producing phase within it
 * (F3). `synthesizedAnswer` is always prose; this is the structured alternative,
 * so a caller wanting the winner or the tally does not have to parse English.
 *
 * A parse failure in the producing feature falls back to `type: "NONE"` with
 * `raw` set to the unparsed text rather than dropping the record — so `type`
 * being NONE with a non-empty `raw` means "we tried and could not read it", not
 * "nothing happened".
 */
export interface DecisionRecord {
  type: DecisionType;
  /** Human-readable one-liner, safe to display without interpreting `tally`. */
  outcome: string | null;
  /** The winning side/option/agent; `null` for a tie or a non-competitive agreement. */
  winner: string | null;
  /** Structured detail specific to `type` — side→score for VERDICT, option→weight for VOTE. */
  tally: Record<string, unknown> | null;
  /** Members who disagreed. Empty (never null) when nobody did or dissent-recording is off. */
  dissents: Dissent[];
  /** Free-text tag naming the mechanism, e.g. "debate-judgment", "majority". */
  method: string | null;
  /** Name of the phase that produced this decision. */
  decidedAtPhase: string | null;
  /** The unparsed source text, kept for audit even when `type` is NONE. */
  raw?: string | null;
}

/**
 * Where a pause landed inside a running SEQUENTIAL phase (F2), so a resume skips
 * the speakers that already ran. PARALLEL phases never produce one — their
 * resume re-runs the whole fan-out.
 */
export interface ResumePoint {
  phaseIdx: number;
  repeatIdx: number;
  speakerIdx: number;
  /**
   * Free-text tag for observability; no resume logic reads it. A HUMAN_TURN
   * pause (I6) sets backend `ResumePoint` constants `"HUMAN_TURN"` or
   * `"HUMAN_TURN_PARALLEL"`.
   */
  pauseKind: string | null;
}

/** Proposal status (I11). The backend's own Javadoc mentions a `"REJECTED"` status, but no such
 *  constant or code path exists — only these two are ever actually set. */
export type ProposalStatus = "OPEN" | "SUPERSEDED";

/**
 * One proposal on the negotiation table (I11). `acceptanceEntryIndices` maps
 * agentId → the absolute transcript index of that agent's accepting entry — the
 * signed transcript entries themselves ARE the co-signatures (no separate
 * crypto), and those indices are what an AGREEMENT decision's
 * `tally.signedAcceptances` quotes.
 */
export interface Proposal {
  /** Stable id ("p1", "p2", …) — what a BARGAIN turn's accept names. */
  id: string;
  byAgentId: string;
  /** The phase repeat this proposal was made in. */
  round: number;
  /** The proposal's terms — a plain string in v1, deliberately untyped. */
  terms: string;
  status: ProposalStatus;
  acceptedBy: string[];
  acceptanceEntryIndices: Record<string, number>;
}

/**
 * One concession ledger line (I11): what an agent gave up, and what they
 * received in return — every concession must name its counterpart, which is
 * what stops sycophantic instant-agreement.
 *
 * NOTE: this is the shape of `NegotiationState.concessions[]` (field name
 * `byAgentId`, matching the backend record exactly). A DecisionRecord's
 * `tally.signedAcceptances`/concession summary for an AGREEMENT decision
 * serializes the SAME data under the key `"by"` instead — a naming
 * inconsistency between the live ledger and the decision tally snapshot, not a
 * typo here.
 */
export interface Concession {
  byAgentId: string;
  round: number;
  gaveUp: string;
  inReturnFor: string;
  refProposalId: string;
}

/** The negotiation's working state (I11) — open/superseded proposals and the concession ledger, persisted with the document. */
export interface NegotiationState {
  proposals: Proposal[];
  concessions: Concession[];
}

/**
 * A HUMAN group member's turn is up (I6) — what the member's own submission UI
 * renders. `entryType` is the transcript entry type their response will be
 * recorded as once submitted (the phase's natural type, mirroring what an agent
 * in the same seat would produce). `onTimeout` mirrors the config's policy at
 * pause time (a later config edit does not retroactively change an
 * already-pending turn).
 */
export interface PendingHumanInput {
  memberId: string;
  displayName: string;
  phaseIdx: number;
  repeatIdx: number;
  speakerIdx: number;
  entryType: string;
  renderedPrompt: string;
  onTimeout: OnHumanTimeout;
  requestedAt: string;
}

/** How consumers should read a `SharedArtifact.content` string (I17). */
export type ArtifactType = "TEXT" | "MARKDOWN" | "JSON";

/** Editing lifecycle (I17): DRAFT while being worked, FINAL once frozen. `markFinal` is a tool-call param only — persists as this `status`. */
export type ArtifactStatus = "DRAFT" | "FINAL";

/** One superseded revision of an artifact (I17). `version` is the version this content carried while current. */
export interface ArtifactRevision {
  content: string;
  editorAgentId: string;
  version: number;
  at: string;
}

/**
 * A typed document group members co-create through artifact tools (I17,
 * blackboard-lite) — lives in its own backend collection, never embedded in the
 * `GroupConversation` document. Only ever arrives on the response of a
 * single-conversation fetch (`getGroupConversation` / SSE has no snapshot
 * endpoint) — list/discuss/followup/continue/close responses never populate
 * this array; track updates incrementally via the `artifact_updated` SSE event
 * if a live view is needed between fetches.
 *
 * Hard ceiling: {@link ARTIFACT_MAX_CONTENT_BYTES} UTF-8 bytes on `content`.
 * History is capped at {@link ARTIFACT_HISTORY_CAP} entries, oldest dropped.
 */
export interface SharedArtifact {
  id: string;
  groupConversationId: string;
  ownerUserId: string;
  name: string;
  type: ArtifactType;
  content: string;
  /** Monotonic edit counter starting at 1 — the CAS token every update must present. */
  version: number;
  lastEditorAgentId: string | null;
  status: ArtifactStatus;
  history: ArtifactRevision[];
  createdAt: string;
  updatedAt: string;
}
export const ARTIFACT_MAX_CONTENT_BYTES = 256 * 1024;
export const ARTIFACT_HISTORY_CAP = 10;

export interface GroupConversation {
  id: string;
  groupId: string;
  userId: string;
  state: GroupConversationState;
  originalQuestion: string;
  transcript: TranscriptEntry[];
  memberConversationIds: Record<string, string>;
  /**
   * agentId → display name for every member the discussion has ever had,
   * including runtime recruits. This is what `followupGroupMember` resolves a
   * human-typed name against.
   */
  memberDisplayNames?: Record<string, string>;
  /** Per-member accumulated cost in USD (F5 cost ledger). */
  memberCosts?: Record<string, number>;
  /** Accumulated cost of the whole discussion in USD (F5) — what I1's ceiling bounds. */
  totalCost?: number;
  /** agentId → that member's one-line stance, for the overview dashboard. */
  memberStances?: Record<string, MemberStance>;
  currentPhaseIndex: number;
  currentPhaseName: string | null;
  synthesizedAnswer: string | null;
  /**
   * Structured conclusion (F3) — a debate verdict today, votes/agreements/awards
   * later. `null` until a decision-producing phase runs.
   */
  decision?: DecisionRecord | null;
  depth: number;
  /** Task list for TASK_FORCE style discussions */
  taskList: SharedTaskList | null;
  /** Agents dynamically added during the discussion */
  dynamicMembers: GroupMember[];
  /** Agent IDs created during this discussion (for lifecycle cleanup) */
  createdAgentIds: string[];
  /**
   * Agent IDs recruited into this discussion (I7). Distinct from
   * `createdAgentIds`: recruits are pre-existing deployed agents the discussion
   * borrowed, so teardown never undeploys them.
   */
  recruitedAgentIds?: string[];
  /** Agent IDs retained by creators (agent-decides policy) */
  retainedAgentIds: string[];
  /**
   * Current discussion round (1-based). Incremented by streamGroupContinue();
   * `undefined` on legacy documents that predate the field.
   */
  round?: number;
  /**
   * Transcript index where the CURRENT round's entries begin. Scans that must not
   * pick up a previous round's conclusion (latest synthesis, verdict, dissents)
   * start here. `0`/absent means "the whole transcript", which is exactly right
   * for a first round.
   */
  roundStartTranscriptIndex?: number;
  /** The question a `continue` round is running, when different from `originalQuestion`. */
  resumeQuestion?: string | null;
  /** Document schema version (F6). Informational — the backend migrates on read. */
  schemaVersion?: number;
  /**
   * Backend-computed list of operations currently available on this conversation
   * (READ_ONLY, always recomputed from `state` server-side — never trusted from a
   * stored document). Drives the post-COMPLETED action bar. Absent on responses
   * from older backends.
   */
  availableActions?: GroupConversationAction[];
  created: string;
  lastModified: string;
  // HITL pause fields (set when state === "AWAITING_APPROVAL")
  pausedAtPhaseIndex?: number;
  pausedTurnCount?: number;
  pausedPhaseName?: string;
  pausedAt?: string;
  hitlPauseType?: HitlGranularity;
  hitlPauseReason?: string;
  hitlTimeoutPolicy?: HitlTimeoutPolicy;
  hitlApprovalTimeout?: string;
  /** The group's `approvalContext` rendered when this pause was committed; `null` when none is configured. */
  hitlApprovalContext?: ApprovalContextView | null;
  /** Where inside a SEQUENTIAL phase the pause landed, so a resume skips what already ran (F2). */
  resumePoint?: ResumePoint | null;
  /**
   * Repeat-slice base a paused SEQUENTIAL phase's resume rewinds to (I6), `-1`
   * when not mid-repeat-slice. Informational — the backend resumes correctly
   * without a client reading this; exposed for observability.
   */
  pausedRepeatSliceBase?: number;
  /** Set when `state === "AWAITING_HUMAN_INPUT"` — the pending member's turn to render and submit. */
  pendingHumanInput?: PendingHumanInput | null;
  /** Open/superseded proposals and the concession ledger for a NEGOTIATION-style discussion (I11). `null` until the first PROPOSAL/BARGAIN phase produces state. */
  negotiation?: NegotiationState | null;
  /**
   * A facilitator-diverged phase list (I12) — present once a facilitator move
   * (EXTEND_PHASE, CALL_VOTE) has altered the plan away from the config's own
   * `phases`. `null`/absent = the config's phases are still authoritative.
   */
  runtimePhases?: DiscussionPhase[] | null;
  /** Executed non-CONTINUE facilitator moves so far this discussion (I12), capped by `FacilitatorConfig.maxMovesPerDiscussion`. */
  facilitatorMoveCount?: number;
  /** EXTEND_PHASE count per phase index (I12), keyed by the phase index as a string. Capped at 2 per phase. */
  facilitatorExtensions?: Record<string, number>;
  /** Rolling summary of transcript[0, summaryUpToIndex) for FULL-scope windowing (I9). `null` until the window first overflows. */
  transcriptSummary?: string | null;
  summaryUpToIndex?: number;
  /**
   * SEPARATE rolling summary built only from "Anonymous"-labelled input, so an
   * ANONYMOUS-scope phase's summary can never leak real speaker names (I9).
   */
  anonymousTranscriptSummary?: string | null;
  anonymousSummaryUpToIndex?: number;
  /**
   * The discussion's shared artifacts (I17). Populated ONLY by
   * `getGroupConversation` (READ_ONLY, recomputed server-side from the artifact
   * store on every such read) — absent/undefined on every other response shape
   * (list, discuss, followup, continue, close, SSE). A stored copy of this array
   * must never be trusted back to the backend.
   */
  artifacts?: SharedArtifact[];
}

/**
 * One attachment on a group discussion request. Deliberately NOT the
 * `AttachmentRef` of `lib/api/attachments.ts`: that one references a `storageRef`
 * already uploaded to an existing conversation, and a group conversation does not
 * exist until this call creates it. The backend therefore takes the content
 * itself — inline base64 `data` (+ `mimeType`) or a hosted `url` — stores it bound
 * to the new group conversation and grants every member access.
 *
 * Backend ceilings: at most {@link MAX_GROUP_ATTACHMENTS} per request, a `url` of
 * 2048 chars, a `fileName` of 255, a `mimeType` of 255, and `data` bounded by
 * `eddi.attachments.max-size-bytes`.
 */
export interface GroupAttachmentRef {
  mimeType?: string | null;
  /** Base64 payload WITHOUT a data: URI prefix. Mutually exclusive with `url`. */
  data?: string | null;
  url?: string | null;
  fileName?: string | null;
}

/** Backend `IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST`. */
export const MAX_GROUP_ATTACHMENTS = 50;

/**
 * Client-side ceiling on the COMBINED size of one request's attachments.
 *
 * The backend bounds each file and the file count, but not the total — and the
 * two interact badly here, because this endpoint takes the bytes inline rather
 * than by reference. Fifty files at the per-file limit is ~1 GiB held in memory
 * as base64 (which inflates by a third) and then serialized into a single JSON
 * body: the tab dies long before the server gets a chance to reject it.
 *
 * Necessarily larger than {@link MAX_ATTACHMENT_BYTES}, or a single legal file
 * could not be attached. 32 MiB (~43 MiB encoded) leaves room for one maximal
 * file plus several ordinary ones, and stays under the body limit a default
 * reverse proxy imposes.
 */
export const MAX_GROUP_ATTACHMENTS_TOTAL_BYTES = 32 * 1024 * 1024;

/** Backend `IRestGroupConversation.MAX_QUESTION_CHARS`. */
export const MAX_GROUP_QUESTION_CHARS = 50_000;

// ─── SSE Streaming ──────────────────────────────────────────────

export type GroupSSEEventType =
  | "group_start"
  // Emitted by the /continue/stream endpoint at the start of a new round.
  | "round_start"
  | "phase_start"
  | "speaker_start"
  | "speaker_complete"
  | "phase_complete"
  | "synthesis_start"
  | "group_complete"
  // Generic terminal failure. Also carries expected approve/stream resume
  // rejections (e.g. 409 concurrent decision, 400 invalid taskApprovals) — the
  // backend emits these as "group_error", never a bare "error", which would
  // collide with the browser EventSource transport-error event (EDDI issue #36).
  | "group_error"
  | "task_plan_created"
  | "task_verified"
  | "awaiting_approval"
  | "hitl_resume"
  | "cancelled"
  // A member agent's own conversation paused mid-turn (unsupported in a group);
  // its turn is recorded SKIPPED with a reason.
  | "member_pause_skipped"
  // A convergence check ran after a phase repeat (I2). Fires on EVERY check,
  // converged or not, so an observer sees a phase approaching agreement rather
  // than only the moment it stops.
  | "convergence_checked"
  // A phase stopped repeating early because its participants converged (I2).
  // Always preceded by a convergence_checked for the same repeat.
  | "convergence_reached"
  // A DecisionRecord was set on the discussion (F3) — VERDICT, VOTE (I14) and
  // AGREEMENT (I11) all confirmed wired; AWARD (I18) not yet confirmed.
  | "decision_reached"
  // A HUMAN member's turn is up (I6). Terminal — closes the stream, same as
  // awaiting_approval; the pending turn's detail lives on `pendingHumanInput`.
  | "human_input_requested"
  // A RETRO phase harvested lessons into group memory (I8). NOT terminal — a
  // retro can precede further phases. Fires even with zero lessons stored.
  | "retro_recorded"
  // A member created or updated a shared artifact (I17). NOT terminal. Carries
  // metadata only, never content.
  | "artifact_updated"
  // A cost attribution landed in the discussion ledger. NOT terminal — fires
  // after every attribution, including system spend (the I9 window summarizer
  // and the stance summarizer), whose key names no member.
  | "cost_updated"
  // A member's one-line stance was recomputed at a phase boundary. NOT
  // terminal, and fires only when the stance TEXT actually changed.
  | "stance_updated"
  // A member's tool loop is about to run a tool. NOT terminal. Name only, never
  // arguments; a best-effort liveness signal for a turn that is not streamed.
  | "member_tool_call";
//
// `token` and `synthesis_complete` are declared in the backend's
// GroupConversationEventSink but no producer emits them; they are deliberately
// absent here rather than modelled as dead cases. Likewise, a facilitator's own
// LLM turn (I12) produces NO SSE events at all (no speaker_start/token/
// speaker_complete) — only re-fetching the transcript reveals it, except
// ESCALATE_HUMAN, which fires human_input_requested above.

export interface GroupSSEEvent {
  type: GroupSSEEventType;
  data: string;
}

/** Parsed event payloads for convenience.
 *  Field names match the backend's GroupStartEvent Java record. */
export interface GroupStartPayload {
  groupConversationId: string;
  /** @deprecated Use groupConversationId — kept for backwards compatibility */
  conversationId?: string;
  groupId: string;
  question: string;
  style: string;
  totalPhases: number;
  memberAgentIds: string[];
}

/** Opens every continuation round (round 2 onwards) in place of `group_start`. */
export interface RoundStartPayload {
  groupConversationId: string;
  round: number;
  question: string;
  phaseCount: number;
}

export interface PhaseStartPayload {
  phaseIndex: number;
  phaseName: string;
  phaseType: string;
  participants: string;
}

export interface SpeakerStartPayload {
  agentId: string;
  displayName: string;
  phaseIndex: number;
  phaseName: string;
}

/**
 * Why a member's turn produced no contribution (`SpeakerCompleteEvent.outcome`).
 * Absent on an ordinary contribution, and on every event from a backend that
 * predates the field.
 */
export type SpeakerCompleteOutcome = "TIMEOUT" | "SKIPPED" | "ERROR";

export interface SpeakerCompletePayload {
  agentId: string;
  displayName: string;
  /** Backend field name is 'response'. Null when `outcome` is set. */
  response: string | null;
  /** Fallback alias */
  content?: string;
  phaseIndex: number;
  phaseName: string;
  /** Peer-targeted phase: the agent this response was aimed at */
  targetAgentId?: string;
  targetDisplayName?: string;
  /**
   * Set when the turn produced nothing — then `response` is null, so a failure
   * is never rendered as something the member said. The raw error text stays in
   * the server log and the persisted transcript's `errorReason`.
   */
  outcome?: SpeakerCompleteOutcome | null;
}

export interface PhaseCompletePayload {
  phaseIndex: number;
  phaseName: string;
}

export interface SynthesisStartPayload {
  moderatorAgentId: string;
}

export interface GroupCompletePayload {
  state: GroupConversationState;
  synthesizedAnswer: string | null;
}

export interface GroupErrorPayload {
  error: string;
}

export interface TaskPlanCreatedPayload {
  // Matches the backend TaskSummary record: { id, subject, assignedTo, priority }.
  // (assignedTo is the assignee's display name.)
  tasks: { id: string; subject: string; assignedTo: string; priority: number }[];
  preConfigured: boolean;
}

export interface TaskVerifiedPayload {
  taskId: string;
  taskSubject: string;
  passed: boolean;
  feedback: string;
}

/** Payload of `convergence_checked` (I2). */
export interface ConvergenceCheckedPayload {
  phaseIndex: number;
  phaseName: string;
  /** 0-based index of the repeat that was just checked. */
  repeat: number;
  /** The judge's 0..1 score, or `-1` when no judge ran (all abstained, or a parse failure). */
  agreementScore: number;
  /** Whether this check ended the phase's repeats. */
  converged: boolean;
  /** One-line explanation, already display-ready. */
  reason: string;
}

/** Payload of `convergence_reached` (I2). */
export interface ConvergenceReachedPayload {
  phaseIndex: number;
  phaseName: string;
  repeat: number;
  /** How many further repeats the phase was configured for but will not run. */
  repeatsSkipped: number;
  reason: string;
}

/** Payload of `decision_reached` (F3). */
export interface DecisionReachedPayload {
  decision: DecisionRecord;
}

/** Payload of `human_input_requested` (I6). Carries identifiers only — the rendered prompt lives on `pendingHumanInput`. */
export interface HumanInputRequestedPayload {
  memberId: string;
  displayName: string;
  phaseIndex: number;
  phaseName: string;
}

/** Payload of `retro_recorded` (I8). */
export interface RetroRecordedPayload {
  groupId: string;
  phaseName: string;
  lessonsStored: number;
}

/**
 * Payload of `cost_updated`.
 *
 * `attributedCost` is the key's CUMULATIVE cost, never a delta — the backend
 * ledger records by replacement, so a frame replayed after a reconnect is
 * idempotent. Consumers must overwrite their stored value for
 * `attributionKey` rather than adding to it.
 *
 * `totalCost` is a convenience. A PARALLEL phase's simultaneous member turns
 * can interleave, so two frames' totals may arrive out of order; summing the
 * per-key map is order-independent and is what `useGroupDiscussionStream`
 * does.
 */
export interface CostUpdatedPayload {
  /**
   * The ledger key. NOT uniformly an agent id: a member turn uses its agentId,
   * the discussion's own machinery uses a synthetic `system:…` key, and a
   * nested GROUP member uses `agentId:childConversationId`. A consumer that
   * indexes by member must tolerate a key matching no member.
   */
  attributionKey: string;
  /** The member's display name, or `null` for a system key (which names none). */
  displayName: string | null;
  attributedCost: number;
  totalCost: number;
}

/** Payload of `member_tool_call` — the tool's name, never its arguments. */
export interface MemberToolCallPayload {
  agentId: string;
  displayName: string | null;
  tool: string;
}

/** Payload of `stance_updated`. */
export interface StanceUpdatedPayload {
  agentId: string;
  displayName: string | null;
  stance: string;
  /**
   * `true` when a configured summarizer wrote it, `false` when it is the
   * lead-sentence extraction fallback. Surfaced because an extracted line is
   * the member's own words and a generated one is a paraphrase — showing a
   * paraphrase as a quote would misattribute it.
   */
  llmGenerated: boolean;
  /** How many of that member's own contributions the stance reflects. */
  coveredContributions: number;
}

/** Payload of `artifact_updated` (I17). `created` is `true` for a fresh artifact (v1), `false` for an accepted update. */
export interface ArtifactUpdatedPayload {
  artifactId: string;
  name: string;
  type: ArtifactType;
  version: number;
  editorAgentId: string;
  status: ArtifactStatus;
  created: boolean;
}

/**
 * Style display info — icons plus the English fallbacks for
 * `groups.styles.<STYLE>.label` / `.flow`.
 *
 * This is the LAST resort, not the display path: render sites resolve the
 * localized strings through {@link getStyleInfo} (see `@/lib/discussion-styles`).
 *
 * Each label names the *shape of the conversation* the engine actually runs —
 * the phases in `getStylePhases` — and nothing beyond it. Earlier labels
 * ("Quality Review", "Expert Forecast", "Stress Test") named an outcome the
 * group cannot promise: every style, whatever its name, is agents writing text
 * at each other in a fixed turn order.
 */
export const STYLE_INFO: Record<
  DiscussionStyle,
  { label: string; flow: string; icon: string }
> = {
  ROUND_TABLE: {
    label: "Round Table",
    flow: "Everyone gives their view, then builds on the others over several rounds",
    icon: "🗣️",
  },
  PEER_REVIEW: {
    label: "Peer Review",
    flow: "Members answer on their own, critique each other, then revise",
    icon: "🔍",
  },
  DEVIL_ADVOCATE: {
    label: "Devil's Advocate",
    flow: "One member attacks the group's position, the rest defend it",
    icon: "😈",
  },
  DELPHI: {
    label: "Anonymous Rounds",
    flow: "Members answer anonymously and revise each round, so nobody anchors on the first speaker",
    icon: "🔮",
  },
  DEBATE: {
    label: "Pro/Con Debate",
    flow: "One side argues for, one against, then rebuttals and a verdict",
    icon: "⚖️",
  },
  TASK_FORCE: {
    label: "Task Force",
    flow: "The moderator plans the work, members run tasks in parallel, results get verified",
    icon: "🎯",
  },
  NEGOTIATION: {
    label: "Negotiation",
    flow: "Parties trade proposals until they agree; an arbiter steps in only if they do not",
    icon: "🤝",
  },
  CUSTOM: {
    label: "Custom Flow",
    flow: "Phases you define yourself",
    icon: "🛠️",
  },
};

export interface EntryTypeInfo {
  label: string;
  color: string;
}

/** Entry type display info */
export const ENTRY_TYPE_INFO: Record<TranscriptEntryType, EntryTypeInfo> = {
  QUESTION: { label: "Question", color: "blue" },
  OPINION: { label: "Opinion", color: "green" },
  CRITIQUE: { label: "Critique", color: "orange" },
  REVISION: { label: "Revision", color: "teal" },
  CHALLENGE: { label: "Challenge", color: "red" },
  DEFENSE: { label: "Defense", color: "purple" },
  ARGUMENT: { label: "Argument", color: "indigo" },
  REBUTTAL: { label: "Rebuttal", color: "pink" },
  SYNTHESIS: { label: "Synthesis", color: "gold" },
  ERROR: { label: "Error", color: "destructive" },
  SKIPPED: { label: "Skipped", color: "muted" },
  PLAN: { label: "Plan", color: "sky" },
  TASK_RESULT: { label: "Task Result", color: "emerald" },
  VERIFICATION: { label: "Verification", color: "amber" },
  FOLLOW_UP: { label: "Follow-up", color: "blue" },
  ABSTAINED: { label: "Abstained", color: "muted" },
  DISSENT: { label: "Dissent", color: "red" },
  CONVERGENCE: { label: "Convergence", color: "violet" },
  FACILITATION: { label: "Facilitation", color: "sky" },
  VOTE: { label: "Vote", color: "indigo" },
  PROPOSAL: { label: "Proposal", color: "teal" },
  BARGAIN: { label: "Counter-offer", color: "orange" },
  HUMAN_INPUT: { label: "Human input", color: "blue" },
  RETRO: { label: "Retrospective", color: "violet" },
  BID: { label: "Bid", color: "emerald" },
};

/**
 * Display info for a transcript entry type, never `undefined`.
 *
 * The backend's enum grows with each collaboration wave, and a group conversation
 * is rendered by looking its entries' types up in {@link ENTRY_TYPE_INFO}. When a
 * type arrived that this build did not know — `FOLLOW_UP`, which the Manager
 * itself produces, was one — the lookup returned `undefined` and dereferencing
 * `.label` threw, blanking the entire transcript rather than one badge. A
 * newer backend must degrade to an unstyled badge, not to a blank screen.
 */
export function entryTypeInfo(
  // Accepts null/undefined too. The whole point of this function is that no
  // caller has to guard, and a transcript entry arriving without a type — from a
  // hand-written document, or a field renamed upstream — must cost a badge, not
  // the screen.
  type: TranscriptEntryType | string | null | undefined,
): EntryTypeInfo {
  if (!type) return { label: "—", color: "muted" };
  return (
    ENTRY_TYPE_INFO[type as TranscriptEntryType] ?? {
      label: humanizeEntryType(type),
      color: "muted",
    }
  );
}

/** "TASK_RESULT" → "Task Result", for a type this build has no entry for. */
function humanizeEntryType(type: string): string {
  return type
    .split("_")
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(" ");
}
