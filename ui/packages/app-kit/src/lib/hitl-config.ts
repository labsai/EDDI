import {
  NEGOTIATION_ARBITRATION_TEMPLATE,
  type DiscussionPhase,
  type DiscussionStyle,
} from "./api/groups";
// Defined in api/groups.ts, which repairs stored groups with them on read, so
// that module does not import this one.
export { NEGOTIATION_ARBITRATION_TEMPLATE, repairNegotiationArbitration } from "./api/groups";
import type { GroupHitlConfig } from "./api/hitl";

/**
 * Frontend mirror of the backend `DiscussionStylePresets` phase expansion.
 *
 * Needed because preset-style groups store `phases: null` and the backend
 * generates them at runtime — but to let a user mark WHICH phases require human
 * approval (`phase.requiresApproval`, the sole HITL pause trigger) we must
 * materialize the phase list into the saved config. Almost every preset phase
 * uses `inputTemplate: null` (the engine resolves the prompt from the phase
 * TYPE), so replicating name/type/participants/turnOrder/contextScope/repeats
 * here is behavior-preserving. The one exception is NEGOTIATION's Arbitration,
 * which carries its own prompt — see {@link NEGOTIATION_ARBITRATION_TEMPLATE}.
 * Keep in sync with ai.labs.eddi.configs.groups.model.DiscussionStylePresets.
 */
function phase(
  name: string,
  type: DiscussionPhase["type"],
  participants: string,
  turnOrder: DiscussionPhase["turnOrder"],
  contextScope: DiscussionPhase["contextScope"],
  targetEachPeer: boolean,
  repeats: number,
  skipIf: DiscussionPhase["skipIf"] = null,
): DiscussionPhase {
  return {
    name,
    type,
    participants,
    turnOrder,
    contextScope,
    targetEachPeer,
    inputTemplate: null,
    repeats,
    requiresApproval: false,
    // No preset enables either: the backend builds its presets with the
    // constructor that predates I2/I4, and `DiscussionStylePresets` documents
    // DELPHI's recommended convergence config rather than baking it in.
    convergence: null,
    allowAbstention: false,
    skipIf,
  };
}



export function getStylePhases(style: DiscussionStyle, maxRounds: number): DiscussionPhase[] {
  const rounds = Math.max(1, maxRounds || 1);
  switch (style) {
    case "ROUND_TABLE": {
      const phases = [phase("Initial Opinions", "OPINION", "ALL", "SEQUENTIAL", "NONE", false, 1)];
      if (rounds > 1) {
        phases.push(phase("Discussion", "OPINION", "ALL", "SEQUENTIAL", "FULL", false, rounds - 1));
      }
      phases.push(phase("Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1));
      return phases;
    }
    case "PEER_REVIEW":
      return [
        phase("Initial Opinions", "OPINION", "ALL", "PARALLEL", "NONE", false, 1),
        phase("Peer Critique", "CRITIQUE", "ALL", "SEQUENTIAL", "FULL", true, 1),
        phase("Revision", "REVISION", "ALL", "PARALLEL", "OWN_FEEDBACK", false, 1),
        phase("Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
      ];
    case "DEVIL_ADVOCATE":
      return [
        phase("Initial Opinions", "OPINION", "ALL", "PARALLEL", "NONE", false, 1),
        phase("Devil's Challenge", "CHALLENGE", "ROLE:DEVIL_ADVOCATE", "SEQUENTIAL", "FULL", false, 1),
        phase("Defense", "DEFENSE", "ALL", "SEQUENTIAL", "FULL", false, 1),
        phase("Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
      ];
    case "DELPHI": {
      const phases = [phase("Round 1 (Independent)", "OPINION", "ALL", "PARALLEL", "NONE", false, 1)];
      for (let i = 2; i <= rounds; i++) {
        phases.push(phase(`Round ${i} (Anonymous)`, "OPINION", "ALL", "PARALLEL", "ANONYMOUS", false, 1));
      }
      phases.push(phase("Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1));
      return phases;
    }
    case "DEBATE":
      return [
        phase("Opening Arguments (Pro)", "ARGUE", "ROLE:PRO", "SEQUENTIAL", "NONE", false, 1),
        phase("Opening Arguments (Con)", "ARGUE", "ROLE:CON", "SEQUENTIAL", "FULL", false, 1),
        phase("Rebuttal (Pro)", "REBUTTAL", "ROLE:PRO", "SEQUENTIAL", "FULL", false, 1),
        phase("Rebuttal (Con)", "REBUTTAL", "ROLE:CON", "SEQUENTIAL", "FULL", false, 1),
        phase("Judgment", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
      ];
    case "TASK_FORCE":
      return [
        phase("Task Planning", "PLAN", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
        phase("Task Execution", "EXECUTE", "ALL", "PARALLEL", "TASK_ONLY", false, 1),
        phase("Result Verification", "VERIFY", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
        phase("Final Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
      ];
    case "NEGOTIATION":
      // Mirrors DiscussionStylePresets.negotiation(rounds) (I11). Unlike every
      // other preset, `rounds` here drives Bargaining's `repeats` directly, not
      // a count of extra phases.
      return [
        phase("Positions & Interests", "OPINION", "ALL", "PARALLEL", "NONE", false, 1),
        phase("Opening Proposals", "PROPOSAL", "ALL", "SEQUENTIAL", "FULL", false, 1),
        phase("Bargaining", "BARGAIN", "ALL", "SEQUENTIAL", "FULL", false, rounds),
        {
          ...phase("Arbitration", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1, "AGREEMENT_REACHED"),
          inputTemplate: NEGOTIATION_ARBITRATION_TEMPLATE,
        },
        phase("Synthesis", "SYNTHESIS", "MODERATOR", "SEQUENTIAL", "FULL", false, 1),
      ];
    case "CUSTOM":
    default:
      return [];
  }
}

/** Return a copy of `phases` with `requiresApproval` set for the named phases. */
export function applyApprovalPhases(
  phases: DiscussionPhase[],
  approvalPhaseNames: readonly string[],
): DiscussionPhase[] {
  const set = new Set(approvalPhaseNames);
  return phases.map((p) => ({ ...p, requiresApproval: set.has(p.name) }));
}

/** Default group HITL config applied when a user first enables approvals. */
export const DEFAULT_GROUP_HITL_CONFIG: GroupHitlConfig = {
  approvalTimeout: null,
  timeoutPolicy: "WAIT_INDEFINITELY",
  granularity: "PHASE",
  onTaskRejection: "FAIL",
};

// Mirrors java.time.Duration.parse's grammar as used by the backend: an optional
// day component, and a time section that — when the "T" is present — must carry
// at least one component (the (?=\d) lookahead). A fraction is allowed ONLY on
// the seconds field (Duration.parse rejects "PT1.5H"/"PT1.5M"), and a bare
// trailing "T" ("P1DT") is rejected.
// The ISO-8601 duration helpers are @eddi/conversation's (team/duration.ts):
// the human-turn banner counts a member's turn down with them.
export { formatDurationMs, formatIsoDuration, isValidIsoDuration, parseIsoDurationMs } from "@eddi/conversation/team";

/** A finite timeout policy requires a positive approvalTimeout to ever fire. */
export function requiresApprovalTimeout(policy?: string | null): boolean {
  return !!policy && policy !== "WAIT_INDEFINITELY";
}
