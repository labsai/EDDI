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
  // Trim only to decide whether an id was given; compare the ORIGINAL, because
  // the Java helper does `moderator.equals(m.agentId())` on the untrimmed value.
  // With a moderator of " a " and a member "a" the two disagreed: the Manager
  // treated the member as the moderator and hid the note, while the backend
  // treated the moderator as outside the roster and reported the verdict phase.
  const moderatorId = config.moderatorAgentId?.trim() ? config.moderatorAgentId : undefined;
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
 * One reason the backend would refuse to save a group — the hard rejections of
 * `AgentGroupStore.memberAndLimitProblems` and `humanMemberProblems` that a
 * create flow can run into.
 */
export type GroupSaveProblem =
  | { kind: "debateRoles" }
  | { kind: "devilAdvocateRole" }
  | { kind: "memberUnassigned"; count: number }
  | { kind: "humanNeedsName"; count: number }
  | { kind: "humanNeedsId"; count: number }
  | { kind: "humanInTaskForce" }
  | { kind: "humanWithPeerPhases" };

type ProblemMember = {
  agentId?: string | null;
  displayName?: string | null;
  role?: string | null;
  memberType?: string | null;
};

/**
 * What the backend would reject about this group, checked BEFORE a create
 * flow commits to anything.
 *
 * The wizards used to find out at the final `createGroup` — by which point the
 * agent wizard had already created and deployed every new member agent — and
 * then showed a generic error in place of the backend's sentence. The role gap
 * of a DEBATE with nobody on CON, a HUMAN member in a task force, a member with
 * no agent: each left a set of orphaned agents and no explanation.
 *
 * Mirrors the backend exactly, including its scoping: the preset role rule
 * applies only to a group that stores no phases (explicit phases may route
 * roles however they like), and the HUMAN rules expand the preset first, or
 * they would be inert for exactly the groups that need them.
 *
 * `isPendingAgent` marks a member whose agent the flow will create before it
 * saves — it has no id yet, and is not unassigned.
 */
export function groupSaveProblems(
  config: {
    members?: ReadonlyArray<ProblemMember | null> | null;
    phases?: DiscussionPhase[] | null;
    style?: DiscussionStyle | null;
    maxRounds?: number | null;
  },
  isPendingAgent: (member: ProblemMember, index: number) => boolean = () => false,
): GroupSaveProblem[] {
  const members = (config.members ?? []).filter((m): m is ProblemMember => !!m);
  const problems: GroupSaveProblem[] = [];

  const unassigned = members.filter(
    (m, i) => m.memberType !== "HUMAN" && !m.agentId?.trim() && !isPendingAgent(m, i),
  ).length;
  if (unassigned > 0) problems.push({ kind: "memberUnassigned", count: unassigned });

  if (!config.phases || config.phases.length === 0) {
    // Trimmed and upper-cased, like `presetRoleProblems`.
    const roles = new Set(members.map((m) => m.role?.trim().toUpperCase()).filter(Boolean));
    if (config.style === "DEBATE" && (!roles.has("PRO") || !roles.has("CON"))) {
      problems.push({ kind: "debateRoles" });
    }
    if (config.style === "DEVIL_ADVOCATE" && !roles.has("DEVIL_ADVOCATE")) {
      problems.push({ kind: "devilAdvocateRole" });
    }
  }

  const humans = members.filter((m) => m.memberType === "HUMAN");
  if (humans.length > 0) {
    const noName = humans.filter((m) => !m.displayName?.trim()).length;
    if (noName > 0) problems.push({ kind: "humanNeedsName", count: noName });
    const noId = humans.filter((m) => !m.agentId?.trim()).length;
    if (noId > 0) problems.push({ kind: "humanNeedsId", count: noId });

    const phases =
      config.phases && config.phases.length > 0
        ? config.phases
        : getStylePhases(config.style ?? "ROUND_TABLE", config.maxRounds ?? 1);
    if (phases.some((p) => p && (p.type === "PLAN" || p.type === "EXECUTE" || p.type === "VERIFY"))) {
      problems.push({ kind: "humanInTaskForce" });
    }
    if (phases.some((p) => p?.targetEachPeer)) {
      problems.push({ kind: "humanWithPeerPhases" });
    }
  }
  return problems;
}

/** The reader-facing sentence for one {@link GroupSaveProblem}. */
export function groupSaveProblemMessage(t: TFunction, problem: GroupSaveProblem): string {
  switch (problem.kind) {
    case "debateRoles":
      return t(
        "groups.saveProblem.debateRoles",
        "A debate needs at least one member with the role PRO and one with the role CON.",
      );
    case "devilAdvocateRole":
      return t(
        "groups.saveProblem.devilAdvocateRole",
        "A Devil's Advocate group needs a member with the role DEVIL_ADVOCATE.",
      );
    case "memberUnassigned":
      return t("groups.saveProblem.memberUnassigned", {
        defaultValue: "{{count}} member has no agent assigned.",
        defaultValue_other: "{{count}} members have no agent assigned.",
        count: problem.count,
      });
    case "humanNeedsName":
      return t("groups.saveProblem.humanNeedsName", {
        defaultValue: "{{count}} human member needs a display name.",
        defaultValue_other: "{{count}} human members need a display name.",
        count: problem.count,
      });
    case "humanNeedsId":
      return t("groups.saveProblem.humanNeedsId", {
        defaultValue: "{{count}} human member needs the person's user id.",
        defaultValue_other: "{{count}} human members need the person's user id.",
        count: problem.count,
      });
    case "humanInTaskForce":
      return t(
        "groups.saveProblem.humanInTaskForce",
        "Human members cannot join a task-force group (planning, execution and verification phases).",
      );
    case "humanWithPeerPhases":
      return t(
        "groups.saveProblem.humanWithPeerPhases",
        "Human members cannot join a group whose phases address each peer in turn, such as peer review.",
      );
  }
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
 *
 * Every other field is carried through untouched. This used to rebuild the
 * block from the three fields it normalizes, so the Workforce settings page —
 * which loads and saves through it — dropped `assignmentMode` on every save,
 * and a group set to BID assignment quietly went back to the backend's ROLE
 * default.
 */
export function normalizeGroupTaskConfig(config: Partial<GroupTaskConfig>): GroupTaskConfig {
  const perDiscussion = config.maxAgentAddedTasksPerDiscussion;
  const perTurn = config.maxPerTurn;
  return {
    ...config,
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
