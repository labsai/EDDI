import { api } from "../api-client";

// ─── Types ───────────────────────────────────────────────────────────────────

export interface OrphanInfo {
  resourceUri: string;
  type: string;
  name: string;
  deleted: boolean;
}

export interface OrphanReport {
  totalOrphans: number;
  deletedCount: number;
  orphans: OrphanInfo[];
}

// ─── API Functions ───────────────────────────────────────────────────────────

/** Scan for orphaned resources (dry-run). */
export function scanOrphans(includeDeleted = false): Promise<OrphanReport> {
  return api.get<OrphanReport>(
    `/administration/orphans?includeDeleted=${includeDeleted}`
  );
}

/** Permanently purge all orphaned resources. */
export function purgeOrphans(includeDeleted = true): Promise<OrphanReport> {
  return api.delete<OrphanReport>(
    `/administration/orphans?includeDeleted=${includeDeleted}`
  );
}
