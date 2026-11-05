import { http, HttpResponse, type JsonBodyType } from "msw";
import type { TeamBudgetMonth, TeamBudgetSnapshot } from "@/lib/api/team-budget";

/**
 * Per-team monthly budgets, shaped like the backend's `RestGroupBudget` and
 * `RestTeamBudgets`: a snapshot per group, a PUT that refuses a cap below the
 * spend (400 with `error`), and an idempotent transfer keyed on the
 * `Idempotency-Key` header. The mock is stateful; `resetTeamBudgetMock()`
 * restores the seed.
 */

function seed(): Record<string, TeamBudgetSnapshot> {
  const base = { month: "2026-11", resetsAt: "2026-12-01T00:00:00Z", tenantRemainingUsd: null, adjustmentUsd: 0 };
  return {
    studio: { ...base, groupId: "studio", groupName: "Content Studio", baseUsd: 35, capUsd: 35, spentUsd: 12.5, remainingUsd: 22.5, exhausted: false, limitedBy: "TEAM" },
    radar: { ...base, groupId: "radar", groupName: "Signal Radar", baseUsd: 30, capUsd: 30, spentUsd: 28, remainingUsd: 2, exhausted: false, limitedBy: "TEAM" },
    free: { ...base, groupId: "free", groupName: "No Budget", baseUsd: null, capUsd: null, spentUsd: 0, remainingUsd: null, exhausted: false, limitedBy: "NONE" },
  };
}

let teams = seed();
const appliedTransfers = new Map<string, JsonBodyType>();

/** Restores the seeded teams (tests that adjust or transfer). */
export function resetTeamBudgetMock() {
  teams = seed();
  appliedTransfers.clear();
}

function recompute(s: TeamBudgetSnapshot, capUsd: number): TeamBudgetSnapshot {
  const remaining = Math.max(0, capUsd - s.spentUsd);
  return { ...s, capUsd, adjustmentUsd: capUsd - (s.baseUsd ?? 0), remainingUsd: remaining, exhausted: s.spentUsd >= capUsd };
}

const history: TeamBudgetMonth[] = [
  { groupId: "studio", month: "2026-10", spentUsd: 28, adjustmentUsd: 0, appliedTransferIds: [], adjustments: [] },
];

export const teamBudgetHandlers = [
  http.get("*/groupstore/groups/:groupId/budget/history", ({ params }) =>
    HttpResponse.json({ groupId: params.groupId, months: history.filter((m) => m.groupId === params.groupId) }),
  ),
  http.get("*/groupstore/groups/:groupId/budget", ({ params }) => {
    const team = teams[String(params.groupId)];
    return team ? HttpResponse.json(team) : HttpResponse.json({ error: "Group not found." }, { status: 404 });
  }),
  http.put("*/groupstore/groups/:groupId/budget/current-month", async ({ params, request }) => {
    const team = teams[String(params.groupId)];
    if (!team || team.capUsd === null) {
      return HttpResponse.json({ error: "Group has no teamBudget configured" }, { status: 400 });
    }
    const body = (await request.json()) as { capUsd?: number; reason?: string };
    if (!body.reason || !body.reason.trim()) return HttpResponse.json({ error: "A reason is required" }, { status: 400 });
    if (typeof body.capUsd !== "number" || body.capUsd < team.spentUsd) {
      return HttpResponse.json({ error: "The cap cannot be set below what has already been spent this month." }, { status: 400 });
    }
    teams[team.groupId] = recompute(team, body.capUsd);
    return HttpResponse.json(teams[team.groupId]);
  }),
  http.get("*/administration/team-budgets", ({ request }) => {
    const ids = (new URL(request.url).searchParams.get("groupIds") ?? "").split(",").filter(Boolean);
    return HttpResponse.json({
      month: "2026-11",
      tenantRemainingUsd: null,
      teams: ids.map((id) => teams[id]).filter((t): t is TeamBudgetSnapshot => !!t),
    });
  }),
  http.post("*/administration/team-budgets/transfers", async ({ request }) => {
    const key = request.headers.get("Idempotency-Key") ?? "";
    if (key && appliedTransfers.has(key)) return HttpResponse.json(appliedTransfers.get(key));
    const body = (await request.json()) as { fromGroupId: string; toGroupId: string; amountUsd: number; reason: string };
    const from = teams[body.fromGroupId];
    const to = teams[body.toGroupId];
    if (body.fromGroupId === body.toGroupId) {
      return HttpResponse.json({ error: "A transfer needs two different teams" }, { status: 400 });
    }
    if (!from || !to || from.capUsd === null || to.capUsd === null || !body.reason?.trim() || !(body.amountUsd > 0)) {
      return HttpResponse.json({ error: "A transfer needs two budgeted teams, an amount and a reason." }, { status: 400 });
    }
    if (from.capUsd - body.amountUsd < from.spentUsd) {
      return HttpResponse.json({ error: `Team '${from.groupName}' has only $${(from.remainingUsd ?? 0).toFixed(2)} left this month.` }, { status: 400 });
    }
    teams[from.groupId] = recompute(from, from.capUsd - body.amountUsd);
    teams[to.groupId] = recompute(to, to.capUsd + body.amountUsd);
    const result = { from: teams[from.groupId], to: teams[to.groupId], transferId: key || "generated" };
    if (key) appliedTransfers.set(key, result);
    return HttpResponse.json(result);
  }),
];
