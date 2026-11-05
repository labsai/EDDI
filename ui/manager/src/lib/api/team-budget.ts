import { api } from "../api-client";
import { seg } from "./path-segment";

/* ─── Types (mirror ai.labs.eddi.engine.groups.budget) ─── */

/** Which limit binds first for a team. */
export type BudgetLimit = "TEAM" | "TENANT" | "NONE";

/** `TeamBudgetSnapshot`: a team's budget for the current UTC month. */
export interface TeamBudgetSnapshot {
  groupId: string;
  groupName: string | null;
  /** `YYYY-MM`, UTC. */
  month: string;
  /** The configured monthly cap; `null` when the group has no `teamBudget`. */
  baseUsd: number | null;
  adjustmentUsd: number;
  /** `base + adjustment`; `null` without a budget. */
  capUsd: number | null;
  spentUsd: number;
  remainingUsd: number | null;
  exhausted: boolean;
  /** Start of the next UTC month. */
  resetsAt: string;
  tenantRemainingUsd: number | null;
  limitedBy: BudgetLimit;
}

export interface TeamBudgetAdjustment {
  at: string | null;
  actor: string | null;
  kind: string;
  deltaUsd: number;
  capAfterUsd: number;
  reason: string | null;
  counterparty: string | null;
  transferId: string | null;
}

export interface TeamBudgetMonth {
  groupId: string;
  month: string;
  spentUsd: number;
  adjustmentUsd: number;
  appliedTransferIds: string[];
  adjustments: TeamBudgetAdjustment[];
}

export interface TeamBudgetList {
  month: string;
  tenantRemainingUsd: number | null;
  teams: TeamBudgetSnapshot[];
}

export interface TeamBudgetTransferResult {
  from: TeamBudgetSnapshot;
  to: TeamBudgetSnapshot;
  transferId: string;
}

/** Backend `TeamBudgetService.MAX_REASON_LENGTH`. */
export const TEAM_BUDGET_MAX_REASON_LENGTH = 1000;
/** Backend `IRestTeamBudgets.MAX_LISTED_GROUPS`. */
export const TEAM_BUDGET_MAX_LISTED_GROUPS = 50;

/* ─── Calls ─── */

export function getTeamBudget(groupId: string): Promise<TeamBudgetSnapshot> {
  return api.get<TeamBudgetSnapshot>(`/groupstore/groups/${seg(groupId)}/budget`);
}

export async function getTeamBudgetHistory(groupId: string, months = 6): Promise<TeamBudgetMonth[]> {
  const body = await api.get<{ months?: TeamBudgetMonth[] }>(`/groupstore/groups/${seg(groupId)}/budget/history?months=${months}`);
  return body.months ?? [];
}

/** Snapshots of the listed groups the caller may view (at most 50 ids). */
export function listTeamBudgets(groupIds: readonly string[]): Promise<TeamBudgetList> {
  const ids = groupIds.slice(0, TEAM_BUDGET_MAX_LISTED_GROUPS).map((id) => seg(id)).join(",");
  return api.get<TeamBudgetList>(`/administration/team-budgets?groupIds=${ids}`);
}

/** Admin only. Sets this month's cap; the server refuses a cap below the spend. */
export function setCurrentMonthCap(groupId: string, capUsd: number, reason: string): Promise<TeamBudgetSnapshot> {
  return api.put<TeamBudgetSnapshot>(`/groupstore/groups/${seg(groupId)}/budget/current-month`, { capUsd, reason });
}

export interface TransferRequest {
  fromGroupId: string;
  toGroupId: string;
  amountUsd: number;
  reason: string;
}

/**
 * Admin only. The `Idempotency-Key` makes a retry a no-op: generate one per
 * dialog opening (see `newIdempotencyKey`), not per click.
 */
export function transferTeamBudget(request: TransferRequest, idempotencyKey: string): Promise<TeamBudgetTransferResult> {
  return api.post<TeamBudgetTransferResult>("/administration/team-budgets/transfers", request, { "Idempotency-Key": idempotencyKey });
}

export function newIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/* ─── Helpers ─── */

/** Fraction of the cap spent, clamped to 0..1; 0 without a cap. */
export function spentFraction(snapshot: Pick<TeamBudgetSnapshot, "capUsd" | "spentUsd">): number {
  if (!snapshot.capUsd || snapshot.capUsd <= 0) return 0;
  return Math.min(1, Math.max(0, snapshot.spentUsd / snapshot.capUsd));
}

/** Parses a typed USD amount; `null` when it is not a positive finite number. */
export function parseUsd(text: string): number | null {
  const value = Number(text.trim());
  return text.trim() !== "" && Number.isFinite(value) && value > 0 ? value : null;
}
