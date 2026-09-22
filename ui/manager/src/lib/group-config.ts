import type { TFunction } from "i18next";
import {
  DEFAULT_MAX_AGENT_TASKS_PER_DISCUSSION,
  DEFAULT_MAX_AGENT_TASKS_PER_TURN,
  DEFAULT_MAX_DELEGATION_DEPTH,
  DEFAULT_DELEGATION_TIMEOUT_SECONDS,
  type AgentGroupConfiguration,
  type ConvergenceConfig,
  type DiscussionStyle,
  type DecisionRecord,
  type DiscussionPhase,
  type GroupTaskConfig,
} from "./api/groups";
import { getStylePhases } from "./hitl-config";

/**
 * Config-level rules the backend applies at save time but only reports to its own
 * log. A designer who never reads the server log finds out a phase could not run
 * the way they wrote it only by running a discussion — so the same rules are
 * mirrored here and surfaced in the editor, where they are still cheap to fix.
 *
 * Keep in sync with `ai.labs.eddi.configs.groups.mongo.AgentGroupStore`.
 */

/**
 * Phases this config restricts to a moderator it does not have.
 *
 * The engine substitutes the first member by speaking order and says so at
 * runtime, but that is a silent stand-in the author never asked for.
 *
 * Mirrors `AgentGroupStore.moderatorlessPhaseNames`, including the part that is
 * easy to get wrong: checking the stored `phases` alone makes this inert for
 * exactly the configs that need it, because a preset-style group stores NO
 * phases and every one of the six presets ends in a `participants: "MODERATOR"`
 * phase.
 */
export function moderatorlessPhaseNames(
  config: Pick<AgentGroupConfiguration, "moderatorAgentId" | "phases" | "style" | "maxRounds">,
): string[] {
  const moderator = config.moderatorAgentId;
  if (moderator && moderator.trim()) return [];

  const phases: DiscussionPhase[] =
    config.phases && config.phases.length > 0
      ? config.phases
      : getStylePhases(config.style ?? "ROUND_TABLE", config.maxRounds ?? 2);

  return phases
    .filter((p) => p && p.participants?.toUpperCase() === "MODERATOR")
    .map((p) => p.name);
}

/**
 * SYNTHESIS phases that will answer with a **scoring verdict** instead of the
 * prose their moderator's own prompt asks for.
 *
 * Giving members structural roles is what switches this on, and nothing in the
 * configuration says so. A grant board whose members carried `role: PRO` and
 * `role: CON` had its chair return `{"winner": "CON", "scores": {…}}` instead of
 * the recommendation its system prompt specified — correct for a debate-scoring
 * exercise, wrong for anything else, and discoverable only by running it.
 *
 * Informational, not a warning: for a real debate this is the intended
 * behaviour, and the note is its documentation. Setting an `inputTemplate` on
 * the phase is the documented way to opt out.
 *
 * Mirrors `AgentGroupStore.debateVerdictSynthesisPhaseNames`, which in turn
 * mirrors `GroupContextBuilder.isDebateJudgment`. The two config-time
 * substitutions: an `ARGUE`/`REBUTTAL` phase *before* the synthesis stands in
 * for argument entries on the transcript, and only `participants: "MODERATOR"`
 * phases are reported because only those have a speaker resolvable from
 * configuration.
 */
export function debateVerdictSynthesisPhaseNames(
  config: Pick<
    AgentGroupConfiguration,
    "moderatorAgentId" | "phases" | "style" | "maxRounds" | "members"
  >,
): string[] {
  const members = config.members ?? [];
  // Untrimmed on purpose: `GroupContextBuilder.debatingRoles` is not, so to the
  // runtime "PRO" and "PRO " are two sides. Trimming here would make them one
  // and the note would go missing on a roster the runtime does judge.
  const roles = new Set(
    members
      .filter((m) => m && m.role && m.role.trim())
      .map((m) => m.role!.toUpperCase()),
  );
  // The judgment prompt scores one side against another; fewer than two sides
  // never takes this path.
  if (roles.size < 2) return [];

  // A moderator that is itself a debater judges nothing — the runtime refuses to
  // let a partisan score its own debate and falls back to prose. With no
  // moderator named, the engine substitutes the first member by speaking order,
  // which is usually a debater.
  const moderatorId = config.moderatorAgentId?.trim();
  const speaker = moderatorId
    ? members.find((m) => m && m.agentId === moderatorId)
    : [...members]
        .filter(Boolean)
        .sort(
          (a, b) =>
            (a.speakingOrder ?? Number.MAX_SAFE_INTEGER) -
            (b.speakingOrder ?? Number.MAX_SAFE_INTEGER),
        )[0];
  if (speaker?.role && roles.has(speaker.role.toUpperCase())) return [];

  const phases: DiscussionPhase[] =
    config.phases && config.phases.length > 0
      ? config.phases
      : getStylePhases(config.style ?? "ROUND_TABLE", config.maxRounds ?? 2);

  const names: string[] = [];
  let argumentsSoFar = false;
  for (const phase of phases) {
    if (!phase) continue;
    if (
      phase.type === "SYNTHESIS" &&
      // `== null`, not falsy: an empty-string inputTemplate counts as SET, which
      // is what the runtime's `inputTemplate() != null` does. Treating "" as
      // unset would report a phase that actually concludes in prose. The
      // Manager's own editor writes undefined for a blank field, so this only
      // differs for a config saved through REST, import or MCP.
      phase.inputTemplate == null &&
      argumentsSoFar &&
      phase.participants?.toUpperCase() === "MODERATOR"
    ) {
      names.push(phase.name);
    }
    if (phase.type === "ARGUE" || phase.type === "REBUTTAL") argumentsSoFar = true;
  }
  return names;
}

/** One role no member carries, with every phase that is restricted to it. */
export interface RoleCoverageGap {
  role: string;
  phaseNames: string[];
}

/**
 * Phases restricted to a `ROLE:<name>` that no member actually carries.
 *
 * DEBATE addresses `ROLE:PRO`/`ROLE:CON` and DEVIL_ADVOCATE addresses
 * `ROLE:DEVIL_ADVOCATE`; custom phases can address any role. When no member
 * carries the role, `GroupConversationService.resolveParticipants` logs a
 * warning and falls back to ALL members — so the phase is not skipped, it is
 * answered by everyone. A debate with no CON role therefore has the PRO members
 * arguing the CON side too, and the judge rules on that. Nothing validated this
 * before: both wizards let a debate be created with every member role blank.
 *
 * Same expansion rule as {@link moderatorlessPhaseNames}: a preset-style group
 * stores NO phases, so the check must expand the preset or it is inert for
 * exactly the configs that need it.
 *
 * Matching is trimmed and case-insensitive — kinder than exact matching, and a
 * member whose role differs only by case is far more likely a typo we should
 * not punish with a false alarm.
 */
export function uncoveredRolePhases(config: {
  /** Only `role` is read — wizard member slots qualify without a full GroupMember. */
  members?: ReadonlyArray<{ role?: string | null }> | null;
  phases?: DiscussionPhase[] | null;
  style?: DiscussionStyle | null;
  maxRounds?: number | null;
}): RoleCoverageGap[] {
  const phases: DiscussionPhase[] =
    config.phases && config.phases.length > 0
      ? config.phases
      : getStylePhases(config.style ?? "ROUND_TABLE", config.maxRounds ?? 2);

  const memberRoles = new Set(
    (config.members ?? [])
      .map((m) => m.role?.trim().toUpperCase())
      .filter((r): r is string => !!r),
  );

  const gaps = new Map<string, RoleCoverageGap>();
  for (const phase of phases) {
    const participants = phase?.participants?.trim() ?? "";
    if (!participants.toUpperCase().startsWith("ROLE:")) continue;
    const role = participants.slice("ROLE:".length).trim();
    if (!role || memberRoles.has(role.toUpperCase())) continue;
    const gap = gaps.get(role.toUpperCase()) ?? { role, phaseNames: [] };
    gap.phaseNames.push(phase.name);
    gaps.set(role.toUpperCase(), gap);
  }
  return [...gaps.values()];
}

/**
 * A cost ceiling of zero or less would stop the very first turn of every
 * discussion — so `AgentGroupStore` coalesces it to `null` (unlimited) with a
 * warning rather than rejecting it. Saving one therefore means the *opposite* of
 * what was typed, which is worth refusing in the editor instead of discovering
 * from a log line.
 */
export function isValidCostCeiling(value: number | null | undefined): boolean {
  return value == null || (Number.isFinite(value) && value > 0);
}

/** Convergence only acts on a phase that repeats — one pass has nothing to compare against. */
export function convergenceApplies(phase: Pick<DiscussionPhase, "repeats">): boolean {
  return (phase.repeats ?? 1) > 1;
}

/**
 * The backend's `ConvergenceConfig` compact constructor, mirrored so the editor
 * shows the value that will actually be stored rather than the one that was
 * typed: `minRepeats` has a floor of 2, a `threshold` outside (0,1] falls back to
 * 0.8, and a blank judge becomes MODERATOR.
 */
export const CONVERGENCE_MIN_REPEATS_FLOOR = 2;
export const DEFAULT_CONVERGENCE_THRESHOLD = 0.8;

export function normalizeConvergence(config: Partial<ConvergenceConfig>): ConvergenceConfig {
  const threshold = config.threshold;
  return {
    enabled: !!config.enabled,
    minRepeats: Math.max(config.minRepeats ?? CONVERGENCE_MIN_REPEATS_FLOOR, CONVERGENCE_MIN_REPEATS_FLOOR),
    threshold:
      typeof threshold === "number" && threshold > 0 && threshold <= 1
        ? threshold
        : DEFAULT_CONVERGENCE_THRESHOLD,
    judge: config.judge === "SERVICE" ? "SERVICE" : "MODERATOR",
  };
}

/** Off, with both caps at the backend defaults. */
export const DEFAULT_GROUP_TASK_CONFIG: GroupTaskConfig = {
  allowAgentTaskCreation: false,
  maxAgentAddedTasksPerDiscussion: DEFAULT_MAX_AGENT_TASKS_PER_DISCUSSION,
  maxPerTurn: DEFAULT_MAX_AGENT_TASKS_PER_TURN,
};

/**
 * Normalize a task-list block the way the backend's compact constructor does:
 * a non-positive cap falls back to its default rather than meaning "unlimited",
 * because an unbounded write surface for an LLM is never the intent behind a
 * mistyped 0.
 */
export function normalizeGroupTaskConfig(config: Partial<GroupTaskConfig>): GroupTaskConfig {
  const perDiscussion = config.maxAgentAddedTasksPerDiscussion;
  const perTurn = config.maxPerTurn;
  return {
    allowAgentTaskCreation: !!config.allowAgentTaskCreation,
    maxAgentAddedTasksPerDiscussion:
      typeof perDiscussion === "number" && perDiscussion > 0
        ? perDiscussion
        : DEFAULT_MAX_AGENT_TASKS_PER_DISCUSSION,
    maxPerTurn:
      typeof perTurn === "number" && perTurn > 0 ? perTurn : DEFAULT_MAX_AGENT_TASKS_PER_TURN,
  };
}

/**
 * Whether a decision record is worth rendering.
 *
 * A `type` of NONE means no structured decision was produced — the normal
 * outcome for most styles, and not worth a card of its own. The exception is a
 * NONE that carries `raw` or dissents: `raw` is the backend's marker for "a
 * judgment WAS produced but could not be parsed", and hiding that would turn a
 * real failure into a blank space.
 */
export function hasDisplayableDecision(
  decision: DecisionRecord | null | undefined,
): decision is DecisionRecord {
  if (!decision) return false;
  if (decision.type !== "NONE") return true;
  return !!decision.raw?.trim() || (decision.dissents?.length ?? 0) > 0;
}

/** Backend `DynamicAgentConfig` delegation defaults, for display when unset. */
export function effectiveDelegationDepth(value: number | null | undefined): number {
  return typeof value === "number" && value > 0 ? value : DEFAULT_MAX_DELEGATION_DEPTH;
}

export function effectiveDelegationTimeout(value: number | null | undefined): number {
  return typeof value === "number" && value > 0 ? value : DEFAULT_DELEGATION_TIMEOUT_SECONDS;
}

/**
 * Localized label for a `ProtocolConfig` member policy — `onAgentFailure`
 * (SKIP/RETRY/ABORT) and `onMemberUnavailable` (SKIP/FAIL) share the one
 * `groupWizard.policy*` key space.
 *
 * A helper rather than the title-casing expression each call site used to
 * inline, because that expression indexed the value directly and a config whose
 * policy the backend had omitted took the whole page down with it. Unknown and
 * absent values degrade to something readable instead.
 */
export function memberPolicyLabel(t: TFunction, policy: string | null | undefined): string {
  if (!policy) return t("groups.policyNotSet", "—");
  const titled = policy.charAt(0).toUpperCase() + policy.slice(1).toLowerCase();
  return t(`groupWizard.policy${titled}`, titled);
}
