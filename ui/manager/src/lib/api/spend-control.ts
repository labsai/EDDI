import { api } from "../api-client";
import type { ModelPriceCatalog } from "./model-prices";
import type { TenantQuota } from "./quotas";

/* ─── Types (mirror ai.labs.eddi.engine.spendcontrol.model.SpendControlChange) ─── */

export type SpendControlKind = "MODEL_PRICES" | "TENANT_QUOTA";

export type SpendControlStatus = "PENDING" | "APPLIED" | "REJECTED" | "WITHDRAWN" | "STALE" | "EXPIRED";

/**
 * A change to the price catalog or a tenant quota that an MCP client asked for
 * and that would loosen spending. Approving applies `proposedCatalog` /
 * `proposedQuota` exactly — with the approver as actor — and only while the
 * stored control is still the one the proposal was computed against.
 */
export interface SpendControlChange {
  id: string;
  kind: SpendControlKind;
  status: SpendControlStatus;
  tenantId?: string | null;
  baseRevision?: number | null;
  baseQuota?: TenantQuota | null;
  proposedCatalog?: ModelPriceCatalog | null;
  proposedQuota?: TenantQuota | null;
  changes: string[];
  loosening: string[];
  reason: string;
  proposedBy: string;
  channel?: string | null;
  proposedAtMillis: number;
  expiresAtMillis: number;
  decidedBy?: string | null;
  decidedAtMillis?: number | null;
  decisionNote?: string | null;
  outcome?: string | null;
}

/* ─── API ─── */

const BASE = "/administration/spend-control-changes";

/** Pending changes, newest first. Admin only. */
export function getPendingSpendControlChanges(): Promise<SpendControlChange[]> {
  return api.get<SpendControlChange[]>(`${BASE}?status=PENDING&limit=100`);
}

export function approveSpendControlChange(id: string, note?: string): Promise<SpendControlChange> {
  return api.post<SpendControlChange>(`${BASE}/${encodeURIComponent(id)}/approve`, { note: note ?? null });
}

export function rejectSpendControlChange(id: string, note?: string): Promise<SpendControlChange> {
  return api.post<SpendControlChange>(`${BASE}/${encodeURIComponent(id)}/reject`, { note: note ?? null });
}
