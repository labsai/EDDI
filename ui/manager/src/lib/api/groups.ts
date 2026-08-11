import { api } from "../api-client";
import { deleteAgent, type AgentDescriptor } from "./agents";

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

export type GroupConversationState =
  | "CREATED"
  | "IN_PROGRESS"
  | "SYNTHESIZING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"
  | "AWAITING_APPROVAL"
  /**
   * A HUMAN group member's turn is up (I6): the discussion is parked on
   * `pendingHumanInput` until that member submits via `submitHumanInput`, or the
   * group's `humanMemberConfig` timeout policy resolves the turn. Distinct from
   * AWAITING_APPROVAL — this is "you're up", not "approve/reject".
   */
  | "AWAITING_HUMAN_INPUT"
  // Terminal — member conversations ended, ephemeral agents cleaned up, no
  // further follow-ups/continuations (backend GroupConversationState.CLOSED).
  | "CLOSED";

/**
 * Post-COMPLETED lifecycle operations the backend exposes on a group
 * conversation. Mirrors the identifiers returned by the backend's computed
 * `availableActions` field (GroupConversation.getAvailableActions):
 *   - COMPLETED             → ["followup", "continue", "close"]
 *   - FAILED / CANCELLED    → ["close"]
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
   * `"MODERATOR"` (default) runs the group's moderator as judge. `"SERVICE"` is
   * accepted but currently falls back to MODERATOR with a backend warning.
   */
  judge: ConvergenceJudge;
}

export type ConvergenceJudge = "MODERATOR" | "SERVICE";

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

export interface AgentGroupConfiguration {
  name: string;
  description: string;
  members: GroupMember[];
  moderatorAgentId: string | null;
  style: DiscussionStyle;
  maxRounds: number;
  phases: DiscussionPhase[] | null;
  protocol: ProtocolConfig | null;
  /** Pre-configured tasks for TASK_FORCE style (skips PLAN phase) */
  tasks?: TaskDefinition[];
  /** Dynamic agent creation and recruitment configuration */
  dynamicAgents?: DynamicAgentConfig;
  /** Human-in-the-loop approval configuration */
  hitlConfig?: import("./hitl").GroupHitlConfig;
  /**
   * After each SYNTHESIS phase, give every participant who did NOT write the
   * synthesis one short turn to state where they still materially disagree (I4).
   * Non-PASS replies become public DISSENT entries and populate
   * `GroupConversation.decision.dissents`.
   *
   * Opt-in because it costs one extra short call per non-synthesizer — but the
   * alternative is asking the synthesizer to report the objections to its own
   * summary, which is the failure mode a minority report exists to prevent.
   */
  recordDissents?: boolean;
  /** Whether members may file their own tasks mid-discussion (I5). Absent = tools not assembled. */
  taskListConfig?: GroupTaskConfig | null;
  /** Bounds for RETRO-phase lesson harvesting (I8). `null` runs RETRO with defaults (3 per run / 50 stored). */
  retroConfig?: RetroConfig | null;
  /** Transcript windowing for long-running discussions (I9). `null` = windowing off entirely. */
  contextWindow?: ContextWindowConfig | null;
  /** Governs shared artifacts / blackboard-lite (I17). Absent = artifact tools not assembled. */
  artifactConfig?: ArtifactConfig | null;
  /**
   * Turn-timeout policy for HUMAN members (I6). `null` waits indefinitely. A
   * group with any HUMAN member should set this deliberately — the backend
   * accepts the default (wait forever) without complaint.
   */
  humanMemberConfig?: HumanMemberConfig | null;
  /**
   * Bounded adaptive orchestration (I12). Backend field name is `facilitator`
   * (NOT `facilitatorConfig`) — verified directly against
   * `AgentGroupConfiguration.getFacilitator()`. `null`/disabled means no
   * checkpoint ever runs and the discussion costs nothing extra.
   */
  facilitator?: FacilitatorConfig | null;
}

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

/**
 * Coerce a config as it arrives from the backend into the canonical shapes this
 * codebase assumes. Today that is only `lifecyclePolicy`'s wire format (see
 * {@link normalizeLifecyclePolicy}); the seam exists so the next `@JsonValue`
 * enum has one obvious home instead of a normalisation scattered per consumer.
 *
 * Returns the SAME object when nothing needed changing, so callers comparing by
 * reference (dirty tracking) are unaffected on the common path.
 */
export function normalizeGroupConfig<T extends AgentGroupConfiguration>(config: T): T {
  const dynamic = config.dynamicAgents;
  if (!dynamic) return config;
  const canonical = normalizeLifecyclePolicy(dynamic.lifecyclePolicy);
  if (canonical === dynamic.lifecyclePolicy) return config;
  return { ...config, dynamicAgents: { ...dynamic, lifecyclePolicy: canonical } };
}

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
  /** Free-text tag for observability; no resume logic reads it. */
  pauseKind: string | null;
}

/** Backend `ResumePoint` pause-kind constants for a HUMAN_TURN pause (I6). Free text — display only. */
export const RESUME_KIND_HUMAN_TURN = "HUMAN_TURN";
export const RESUME_KIND_HUMAN_TURN_PARALLEL = "HUMAN_TURN_PARALLEL";

/** Proposal status (I11). The backend's own Javadoc mentions a `"REJECTED"` status, but no such
 *  constant or code path exists — only these two are ever actually set. */
export type ProposalStatus = "OPEN" | "SUPERSEDED";
export const PROPOSAL_OPEN: ProposalStatus = "OPEN";
export const PROPOSAL_SUPERSEDED: ProposalStatus = "SUPERSEDED";

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
   * Current discussion round (1-based). Incremented by continueGroupDiscussion();
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
  hitlPauseType?: import("./hitl").HitlGranularity;
  hitlPauseReason?: string;
  hitlTimeoutPolicy?: import("./hitl").HitlTimeoutPolicy;
  hitlApprovalTimeout?: string;
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

// Re-export descriptor type for group descriptors (same shape as agent descriptors)
export type GroupDescriptor = AgentDescriptor;

// ─── API Functions ───────────────────────────────────────────────

// --- Group Config CRUD ---

export function getGroupDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<GroupDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<GroupDescriptor[]>(
    `/groupstore/groups/descriptors?${params.toString()}`
  );
}

export function getGroup(
  id: string,
  version?: number
): Promise<AgentGroupConfiguration> {
  // Backend requires version — omitting it causes 400 (RuntimeUtilities.checkNotNull)
  const versionSuffix = version != null ? `?version=${version}` : "";
  return api
    .get<AgentGroupConfiguration>(`/groupstore/groups/${id}${versionSuffix}`)
    .then(normalizeGroupConfig);
}

export function createGroup(
  config: AgentGroupConfiguration
): Promise<{ location: string }> {
  return api.post<{ location: string }>("/groupstore/groups", config);
}

export function updateGroup(
  id: string,
  version: number,
  config: AgentGroupConfiguration
): Promise<{ location: string }> {
  return api.put(`/groupstore/groups/${id}?version=${version}`, config);
}

export function deleteGroup(
  id: string,
  version: number,
  permanent = true
): Promise<void> {
  const params = new URLSearchParams({
    version: String(version),
    permanent: String(permanent),
  });
  return api.delete(`/groupstore/groups/${id}?${params}`);
}

export function duplicateGroup(
  id: string,
  version: number
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `/groupstore/groups/${id}?version=${version}`
  );
}

/**
 * One entry of `GET /groupstore/groups/styles`.
 *
 * The endpoint returns a JSON ARRAY (see `RestAgentGroupStore.readDiscussionStyles`),
 * one object per `DiscussionStyle` the running backend knows — NOT a map keyed
 * by the enum, and with no display label. `description` is the backend's own
 * English sentence about the style; the UI shows its localized `flow` text
 * instead, so this is read for capability detection, not for display.
 */
export interface DiscussionStyleDescriptor {
  style: string;
  phases: string[];
  description: string;
}

export function getDiscussionStyles(): Promise<DiscussionStyleDescriptor[]> {
  return api.get<DiscussionStyleDescriptor[]>("/groupstore/groups/styles");
}

export function getGroupJsonSchema(): Promise<Record<string, unknown>> {
  return api.get<Record<string, unknown>>("/groupstore/groups/jsonSchema");
}

// --- Group Conversations ---

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

/**
 * Body of a start/continue discussion request. `attachments` is only accepted by
 * the START endpoints — see {@link continueGroupDiscussion}.
 */
function discussBody(
  question: string,
  userId?: string,
  attachments?: GroupAttachmentRef[],
): Record<string, unknown> {
  const body: Record<string, unknown> = {
    question,
    userId: userId || "manager-user",
  };
  // Omit rather than send [] — the backend treats absent and empty the same, and
  // an omitted key keeps the request byte-identical to the pre-attachment one.
  if (attachments?.length) body.attachments = attachments;
  return body;
}

export function startGroupDiscussion(
  groupId: string,
  question: string,
  userId?: string,
  attachments?: GroupAttachmentRef[],
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations`,
    discussBody(question, userId, attachments),
  );
}

export function getGroupConversation(
  groupId: string,
  conversationId: string
): Promise<GroupConversation> {
  return api.get<GroupConversation>(
    `/groups/${groupId}/conversations/${conversationId}`
  );
}

export function listGroupConversations(
  groupId: string,
  limit = 20,
  index = 0
): Promise<GroupConversation[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  return api.get<GroupConversation[]>(
    `/groups/${groupId}/conversations?${params.toString()}`
  );
}

export function deleteGroupConversation(
  groupId: string,
  conversationId: string
): Promise<void> {
  return api.delete(
    `/groups/${groupId}/conversations/${conversationId}`
  );
}

// ─── Post-COMPLETED lifecycle (followup / continue / close) ─────────
//
// Backend truth (EDDI IRestGroupConversation.java, GroupConversation.java):
//   Lifecycle: discuss → COMPLETED → [followup | continue]* → close → CLOSED.
// Each endpoint returns the full, updated GroupConversation (with the recomputed
// `availableActions`). Concurrency/aborts surface as 409; a member agent that
// cannot be reached surfaces as 502; a member agent timeout as 504.

/**
 * Follow up with a single member of a COMPLETED discussion.
 * POST /groups/{groupId}/conversations/{gcId}/followup
 * Body: FollowUpRequest { question, targetAgentId, userId }.
 * `targetAgentId` accepts either a raw agent id OR a member display name.
 * The agent retains full context; both the question and the reply are appended
 * to the group transcript (TranscriptEntryType.FOLLOW_UP).
 * Failures: 400 (missing question/targetAgentId), 404 (conv/member not found),
 * 409 (not COMPLETED / another op in progress), 502 (agent unreachable),
 * 504 (agent timeout).
 */
export function followupGroupMember(
  groupId: string,
  gcId: string,
  question: string,
  targetAgentId: string,
  userId?: string,
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations/${gcId}/followup`,
    { question, targetAgentId, userId: userId || "manager-user" },
  );
}

/**
 * Continue a COMPLETED discussion with a new question — re-runs all phases as a
 * NEW round (the round counter increments) with every agent retaining memory of
 * prior rounds. This is distinct from starting a brand-new discussion.
 * POST /groups/{groupId}/conversations/{gcId}/continue
 * Body: DiscussRequest { question, userId }.
 * NOTE: attachments are NOT supported on a continuation — the backend rejects a
 * request that carries them with 400 (they are only shared with member agents
 * when the discussion first starts), so this binding never sends them.
 * Failures: 400 (missing question / attachments supplied), 404, 409, 502, 504.
 */
export function continueGroupDiscussion(
  groupId: string,
  gcId: string,
  question: string,
  userId?: string,
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations/${gcId}/continue`,
    { question, userId: userId || "manager-user" },
  );
}

/**
 * Permanently close a group conversation — ends all member conversations and
 * cleans up ephemeral agents. No further follow-ups/continuations are accepted;
 * the conversation moves to the terminal CLOSED state.
 * POST /groups/{groupId}/conversations/{gcId}/close
 * Failures: 404 (not found), 409 (not in COMPLETED/FAILED/CANCELLED state).
 */
export function closeGroupConversation(
  groupId: string,
  gcId: string,
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations/${gcId}/close`,
  );
}

/**
 * Submit a HUMAN group member's turn (I6) — records their reply as the pending
 * transcript entry and resumes an AWAITING_HUMAN_INPUT discussion from the next
 * speaker. Only the pending member's own principal (or an admin) may call this;
 * it is a member SPEAKING, not an approval decision, so it is deliberately
 * separate from the HITL approve endpoints even though both resume a paused
 * discussion.
 * POST /groups/{groupId}/conversations/{groupConversationId}/human-input
 * Body: HumanInputRequest { memberId, content }.
 * Failures: 400 (blank content, unknown member, or memberId not matching the
 * pending turn), 403 (not the pending member/admin), 404, 409 (concurrent
 * modification or the discussion left AWAITING_HUMAN_INPUT some other way).
 */
export function submitHumanInput(
  groupId: string,
  gcId: string,
  memberId: string,
  content: string,
): Promise<GroupConversation> {
  return api.post<GroupConversation>(
    `/groups/${groupId}/conversations/${gcId}/human-input`,
    { memberId, content },
  );
}

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
  | "artifact_updated";
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

export interface SpeakerCompletePayload {
  agentId: string;
  displayName: string;
  /** Backend field name is 'response' */
  response: string;
  /** Fallback alias */
  content?: string;
  phaseIndex: number;
  phaseName: string;
  /** Peer-targeted phase: the agent this response was aimed at */
  targetAgentId?: string;
  targetDisplayName?: string;
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
 * Read a Server-Sent Events response body as a stream of parsed group events.
 * Shared by the initial-discussion and approve/resume streaming endpoints.
 */
async function* readGroupSSE(response: Response): AsyncGenerator<GroupSSEEvent> {
  if (!response.ok) {
    // M5 fix: throw a proper Error, not a plain object
    throw new Error(`Group streaming failed: ${response.status} ${response.statusText}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error("No readable stream");

  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      // Normalise CRLF → LF so the split works regardless of server line-ending style
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");

      // Parse SSE lines: "event: <type>\ndata: <data>\n\n"
      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        if (!part.trim()) continue;
        let eventType: GroupSSEEventType | null = null;
        let eventData = "";

        for (const line of part.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as GroupSSEEventType;
          } else if (line.startsWith("data:")) {
            // C3 fix: concatenate multiple data: lines per SSE spec (§9.2.4)
            eventData += (eventData ? "\n" : "") + line.slice(5).trim();
          }
        }

        // Only yield events with an explicit event: type (skip bare data-only chunks)
        if (eventType) {
          yield { type: eventType, data: eventData };
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** POST a JSON body to an SSE endpoint (shared auth/header scaffolding). */
function postSSE(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
  return fetch(`${api.getBaseUrl()}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...api.getAuthHeader(),
    },
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * Start a group discussion via SSE streaming.
 * Returns an async generator yielding SSE events as they arrive.
 * Same pattern as chat's `sendMessageStreaming()`.
 */
export async function* streamGroupDiscussion(
  groupId: string,
  question: string,
  userId?: string,
  signal?: AbortSignal,
  attachments?: GroupAttachmentRef[],
): AsyncGenerator<GroupSSEEvent> {
  const response = await postSSE(
    `/groups/${groupId}/conversations/stream`,
    discussBody(question, userId, attachments),
    signal,
  );
  yield* readGroupSSE(response);
}

/**
 * Continue a COMPLETED discussion (new round) via the SSE streaming endpoint.
 * POST /groups/{groupId}/conversations/{gcId}/continue/stream
 * Emits `round_start` (new round marker) followed by the same events as the
 * initial discussion stream (phase_start, speaker_*, group_complete, …) plus the
 * HITL events. Mirrors `streamGroupDiscussion`. As with the non-streaming
 * `continueGroupDiscussion`, attachments are unsupported on a continuation, so
 * none are sent here (the backend would emit a terminal `group_error`).
 */
export async function* streamGroupContinue(
  groupId: string,
  gcId: string,
  question: string,
  userId?: string,
  signal?: AbortSignal,
): AsyncGenerator<GroupSSEEvent> {
  const response = await postSSE(
    `/groups/${groupId}/conversations/${gcId}/continue/stream`,
    { question, userId: userId || "manager-user" },
    signal,
  );
  yield* readGroupSSE(response);
}

/**
 * Resume a paused group discussion via the approve/stream SSE endpoint.
 * Submits the human decision AND streams the resumed discussion progress
 * (hitl_resume, phase_start, speaker_*, group_complete, …) over one connection.
 */
export async function* streamGroupApproval(
  groupId: string,
  gcId: string,
  request: import("./hitl").GroupApprovalRequest,
  signal?: AbortSignal,
): AsyncGenerator<GroupSSEEvent> {
  const response = await postSSE(
    `/groups/${groupId}/conversations/${gcId}/approve/stream`,
    request,
    signal,
  );
  yield* readGroupSSE(response);
}


// ─── Helpers ─────────────────────────────────────────────────────

/** Parse group resource URI to extract id and version.
 *
 * Accepted formats:
 *   - `eddi://ai.labs.group/groupstore/groups/ID?version=VERSION`
 *   - `/groupstore/groups/ID?version=VERSION`   (Location header path)
 *   - `http://host/groupstore/groups/ID?version=VERSION`
 */
export function parseGroupResourceUri(resource: string): {
  id: string;
  version: number;
} {
  const normalised = resource.startsWith("eddi://")
    ? resource.replace("eddi://", "http://")
    : resource;
  // Use a dummy base so relative paths (Location headers) parse correctly
  const url = new URL(normalised, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  let id = parts[parts.length - 1] ?? resource;
  const hasQueryVersion = url.searchParams.has("version");
  let version = hasQueryVersion
    ? parseInt(url.searchParams.get("version")!, 10)
    : NaN;

  // Handle backend data bug: `version` may be concatenated into the path
  // segment instead of appearing as a `?version=` query param, e.g.
  // "eddi://…/groupstore/groups/IDversion1" instead of "…/ID?version=1"
  if (!hasQueryVersion) {
    const match = id.match(/^(.+?)version(\d+)$/);
    if (match) {
      id = match[1]!;
      version = parseInt(match[2]!, 10);
    }
  }

  return { id, version: isNaN(version) ? 1 : version };
}

/** Group descriptors by ID, keeping the latest version per group */
export function groupGroupsByName(
  groups: GroupDescriptor[]
): (GroupDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    GroupDescriptor & { id: string; version: number }
  >();

  for (const group of groups) {
    const { id, version } = parseGroupResourceUri(group.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...group, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => (b.lastModifiedOn || b.createdOn || 0) - (a.lastModifiedOn || a.createdOn || 0)
  );
}

/** Enriched group descriptor with config-level data (name, description, style, memberCount) */
export type EnrichedGroupDescriptor = GroupDescriptor & {
  id: string;
  version: number;
  memberCount: number;
  style?: DiscussionStyle;
  members: { agentId: string; displayName: string; memberType?: MemberType }[];
};

/**
 * Fetch group descriptors and enrich them with data from full configs.
 * The backend's descriptor endpoint may return empty name/description because
 * those fields live inside AgentGroupConfiguration, not on the descriptor itself.
 * This function batch-fetches each group's config to fill the gaps.
 */
export async function getEnrichedGroupDescriptors(
  limit = 20,
  index = 0,
  filter = ""
): Promise<EnrichedGroupDescriptor[]> {
  const descriptors = await getGroupDescriptors(limit, index, filter);
  const grouped = groupGroupsByName(descriptors);

  // Batch-fetch full configs for groups with empty names
  const enriched = await Promise.all(
    grouped.map(async (g) => {
      try {
        const config = await getGroup(g.id, g.version);
        return {
          ...g,
          name: config.name || g.name,
          description: config.description || g.description,
          memberCount: config.members?.length ?? 0,
          style: config.style,
          members: (config.members ?? []).map((m) => ({
            agentId: m.agentId,
            displayName: m.displayName,
            memberType: m.memberType,
          })),
        } satisfies EnrichedGroupDescriptor;
      } catch {
        return {
          ...g,
          memberCount: 0,
          members: [],
        } satisfies EnrichedGroupDescriptor;
      }
    })
  );

  return enriched;
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

// ─── Bulk Operations ─────────────────────────────────────────────

/**
 * Resolve the current (latest) version of an agent via the backend
 * currentversion endpoint. Returns 1 as fallback if lookup fails.
 */
async function getCurrentAgentVersion(agentId: string): Promise<number> {
  try {
    const version = await api.get<number>(
      `/agentstore/agents/${agentId}/currentversion`,
    );
    return version ?? 1;
  } catch {
    return 1;
  }
}

/**
 * Soft-delete a group and all its member agents.
 * Each member agent is deleted with permanent=false (soft-delete).
 * The group itself is also soft-deleted.
 */
export async function deleteGroupWithMembers(
  groupId: string,
  version: number,
  config: AgentGroupConfiguration,
): Promise<void> {
  // Collect all agent IDs to delete (members + moderator). GROUP members
  // reference a nested group config, and HUMAN members' `agentId` holds a
  // human principal id (I6) — neither is a deployed agent to soft-delete.
  const agentIds = new Set<string>();
  for (const m of config.members) {
    if (m.agentId && m.memberType !== "GROUP" && m.memberType !== "HUMAN") {
      agentIds.add(m.agentId);
    }
  }
  if (config.moderatorAgentId) agentIds.add(config.moderatorAgentId);

  // Soft-delete each agent at its current version (best-effort)
  const memberDeletes = Array.from(agentIds).map(async (agentId) => {
    try {
      const currentVersion = await getCurrentAgentVersion(agentId);
      await deleteAgent(agentId, currentVersion, { permanent: false });
    } catch {
      // Ignore — agent may already be deleted
    }
  });

  await Promise.allSettled(memberDeletes);

  // Soft-delete the group itself
  await deleteGroup(groupId, version, false);
}
