import { api } from "../api-client";
import { seg } from "./path-segment";

// ─────────────────────────────────────────────────────────────────
// Cross-team handoff (docs/team-handoff.md): another team or a
// standalone agent files a task into a group's standing backlog.
// Two-sided consent — the source lists the target, the target lists
// the source — so this module has both halves of the config plus the
// receiving team's intake inbox.
// ─────────────────────────────────────────────────────────────────

/** Backend `HandoffPolicies` limits the UI mirrors rather than lets a save reject. */
export const HANDOFF_MAX_TARGETS = 20;
export const HANDOFF_MAX_TARGET_DESCRIPTION = 300;
export const HANDOFF_MAX_TEMPLATE_CHARS = 1000;
export const HANDOFF_MAX_PER_DAY_CEILING = 500;
export const HANDOFF_MAX_INBOX_CEILING = 100;
/** `[a-z0-9-]{1,40}` — what the model sees in the tool's `team` enum. */
export const HANDOFF_ALIAS_PATTERN = /^[a-z0-9-]{1,40}$/;
/** `agent:<id>` or `group:<id>`, an exact id — wildcards are refused. */
export const HANDOFF_SOURCE_PATTERN = /^(agent|group):\S+$/;

export type IntakeMode = "AUTO" | "REVIEW";
export type DecisionKind = "VERDICT" | "VOTE" | "AGREEMENT" | "AWARD";
export const HANDOFF_DECISION_KINDS: DecisionKind[] = ["VERDICT", "VOTE", "AGREEMENT", "AWARD"];

export interface HandoffTarget {
  alias: string;
  groupId: string;
  description?: string | null;
  /** 0..10, default 5 */
  defaultPriority?: number | null;
}

export interface AcceptRule {
  source: string;
  /** Default `REVIEW`. */
  mode?: IntakeMode | null;
  maxPerDay?: number | null;
}

export interface AcceptPolicy {
  from: AcceptRule[];
  maxPerDay?: number;
  maxInbox?: number;
}

export interface DecisionRule {
  target: string;
  decisionTypes: DecisionKind[];
  requireWinner?: boolean | null;
  subjectTemplate: string;
  descriptionTemplate?: string | null;
  priority?: number | null;
}

/** `AgentGroupConfiguration.handoffConfig`. */
export interface GroupHandoffConfig {
  targets?: HandoffTarget[];
  /** `ALL`, `MODERATOR` (default) or `ROLE:<role>` */
  toolParticipants?: string;
  maxPerTurn?: number;
  maxPerDiscussion?: number;
  maxDescriptionChars?: number;
  onDecision?: DecisionRule[];
  accept?: AcceptPolicy | null;
}

export type HandoffTrigger = "TOOL" | "DECISION";
export type HandoffIntake = "AUTO" | "PENDING" | "APPROVED" | "APPROVED_EDITED";

/** Where a handed-off task came from (backend `WorkspaceHandoffs.HandoffOrigin`). */
export interface HandoffOrigin {
  handoffId: string;
  sourceKind: "AGENT" | "GROUP";
  sourceId: string;
  sourceVersion?: number | null;
  sourceConversationId?: string | null;
  sourceMemberAgentId?: string | null;
  trigger: HandoffTrigger;
  decisionRef?: string | null;
  artifactId?: string | null;
  reference?: string | null;
  /** How many team boundaries the work has crossed, including this one. */
  hop: number;
  lineage: string[];
  dedupeKey: string;
  filedAt: string;
  intake: HandoffIntake;
  reviewedAt?: string | null;
}

/** One REVIEW item waiting for a human. */
export interface InboxItem {
  handoffId: string;
  subject: string;
  description: string;
  priority: number;
  origin: HandoffOrigin;
}

/** `GET …/workspace/handoffs`, and the `handoffs` field of the workspace document. */
export interface WorkspaceHandoffs {
  inbox: InboxItem[];
  /** backlog task id → origin */
  origins: Record<string, HandoffOrigin>;
}

/** What the source of a handoff is called on screen: the agent's name when we know it, else its id. */
export function describeHandoffSource(
  origin: Pick<HandoffOrigin, "sourceKind" | "sourceId">,
  nameOf?: (id: string) => string,
): string {
  if (origin.sourceKind === "AGENT" && nameOf) return nameOf(origin.sourceId);
  return origin.sourceId;
}

/** Body of {@link acceptTeamHandoff}; any changed field makes the decision APPROVED_EDITED. */
export interface AcceptHandoffRequest {
  subject?: string;
  description?: string;
  priority?: number;
  note?: string;
}

/** Body of {@link declineTeamHandoff}. */
export interface DeclineHandoffRequest {
  /** Short machine-readable reason, recorded in the audit entry. */
  reasonCode?: string;
  note?: string;
}

/**
 * Accept a pending handoff onto the backlog, optionally edited. Needs EDIT on the group.
 * POST /groupstore/groups/{groupId}/workspace/handoffs/{handoffId}/accept
 * Failures: 404 (already decided), 409 (backlog full / duplicate subject), 400 (bad edit).
 */
export function acceptTeamHandoff(
  groupId: string,
  handoffId: string,
  request: AcceptHandoffRequest = {},
): Promise<unknown> {
  return api.post(
    `/groupstore/groups/${seg(groupId)}/workspace/handoffs/${seg(handoffId)}/accept`,
    request,
  );
}

/**
 * Decline a pending handoff; the same handoff is not accepted again for the decline-memory window.
 * POST /groupstore/groups/{groupId}/workspace/handoffs/{handoffId}/decline
 */
export function declineTeamHandoff(
  groupId: string,
  handoffId: string,
  request: DeclineHandoffRequest = {},
): Promise<unknown> {
  return api.post(
    `/groupstore/groups/${seg(groupId)}/workspace/handoffs/${seg(handoffId)}/decline`,
    request,
  );
}

/**
 * Delete a backlog task (and its handoff origin). The backlog is capped at 200 tasks
 * and had no other way to shrink. 409 while a cadence discussion is working the task.
 * DELETE /groupstore/groups/{groupId}/workspace/backlog/{taskId}
 */
export function deleteWorkspaceBacklogTask(groupId: string, taskId: string): Promise<void> {
  return api.delete(`/groupstore/groups/${seg(groupId)}/workspace/backlog/${seg(taskId)}`);
}

export type HandoffProblemCode =
  | "alias"
  | "aliasTwice"
  | "groupId"
  | "tooManyTargets"
  | "role"
  | "source"
  | "sourceTwice"
  | "ruleTarget"
  | "ruleTypes"
  | "ruleSubject";

/** One reason a `handoffConfig` would be refused. `n` is the 1-based row. */
export interface HandoffProblem {
  code: HandoffProblemCode;
  n?: number;
  alias?: string;
}

/**
 * The shape rules the backend's `HandoffConfigValidator` enforces at save time,
 * so the editor can say what is wrong before the round trip. Existence of a
 * target group is deliberately NOT checked (the backend does not either: an
 * imported agent may name a team this instance lacks).
 */
export function handoffProblems(config: GroupHandoffConfig | null | undefined): HandoffProblem[] {
  const problems: HandoffProblem[] = [];
  if (!config) return problems;
  const targets = config.targets ?? [];
  const aliases = new Set<string>();
  targets.forEach((target, i) => {
    const n = i + 1;
    if (!HANDOFF_ALIAS_PATTERN.test(target.alias ?? "")) {
      problems.push({ code: "alias", n });
    } else if (aliases.has(target.alias)) {
      problems.push({ code: "aliasTwice", n, alias: target.alias });
    }
    aliases.add(target.alias);
    if (!target.groupId?.trim()) problems.push({ code: "groupId", n });
  });
  if (targets.length > HANDOFF_MAX_TARGETS) problems.push({ code: "tooManyTargets" });
  const participants = config.toolParticipants ?? "MODERATOR";
  if (participants.startsWith("ROLE:") && !participants.slice(5).trim()) problems.push({ code: "role" });
  const seenSources = new Set<string>();
  (config.accept?.from ?? []).forEach((rule, i) => {
    const n = i + 1;
    if (!HANDOFF_SOURCE_PATTERN.test(rule.source ?? "") || rule.source.includes("*")) {
      problems.push({ code: "source", n });
    } else if (seenSources.has(rule.source)) {
      problems.push({ code: "sourceTwice", n });
    }
    seenSources.add(rule.source);
  });
  (config.onDecision ?? []).forEach((rule, i) => {
    const n = i + 1;
    if (!aliases.has(rule.target)) problems.push({ code: "ruleTarget", n });
    if (rule.decisionTypes.length === 0) problems.push({ code: "ruleTypes", n });
    if (!rule.subjectTemplate.trim()) problems.push({ code: "ruleSubject", n });
  });
  return problems;
}
