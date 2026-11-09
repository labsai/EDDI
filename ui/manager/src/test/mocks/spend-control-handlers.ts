import { http, HttpResponse } from "msw";
import type { SpendControlChange } from "@/lib/api/spend-control";

/**
 * Spend-control changes (`/administration/spend-control-changes`), shaped like
 * the backend's `RestSpendControlChanges`: the list answers the PENDING ones,
 * approve and reject decide one (409 `ALREADY_DECIDED` when it is no longer
 * pending). The mock is stateful and starts empty — no page grows a panel it
 * never asked for; `seedSpendControlMock()` adds changes.
 */

let changes: SpendControlChange[] = [];

export function seedSpendControlMock(next: SpendControlChange[] = []) {
  changes = next.map((c) => ({ ...c }));
}

export const sampleSpendControlChange: SpendControlChange = {
  id: "c-1",
  kind: "MODEL_PRICES",
  status: "PENDING",
  baseRevision: 1,
  changes: ["changed openai/gpt-4o: input 2.5 -> 0.0"],
  loosening: ["changed openai/gpt-4o: input 2.5 -> 0.0"],
  reason: "Provider announced a free tier",
  proposedBy: "admin",
  channel: "mcp",
  proposedAtMillis: Date.parse("2026-11-09T10:00:00Z"),
  expiresAtMillis: Date.parse("2026-11-16T10:00:00Z"),
};

function decide(id: string, status: SpendControlChange["status"]) {
  const change = changes.find((c) => c.id === id);
  if (!change) return HttpResponse.json({ error: `No spend-control change ${id}`, code: "NOT_FOUND" }, { status: 404 });
  if (change.status !== "PENDING") {
    return HttpResponse.json({ error: `${id} is ${change.status}`, code: "ALREADY_DECIDED", status: change.status }, { status: 409 });
  }
  change.status = status;
  change.decidedBy = "admin";
  return HttpResponse.json(change);
}

export const spendControlHandlers = [
  http.get("*/administration/spend-control-changes", ({ request }) => {
    const status = new URL(request.url).searchParams.get("status") ?? "PENDING";
    return HttpResponse.json(status === "ALL" ? changes : changes.filter((c) => c.status === status));
  }),
  http.post("*/administration/spend-control-changes/:id/approve", ({ params }) => decide(String(params.id), "APPLIED")),
  http.post("*/administration/spend-control-changes/:id/reject", ({ params }) => decide(String(params.id), "REJECTED")),
];
