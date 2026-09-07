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
  /**
   * Whether the reference scan finished.
   *
   * When false, the scan failed part-way and resources that are still in use
   * may appear in `orphans`. Every way of building the reference set
   * incompletely makes MORE things look unreferenced, never fewer, so a partial
   * scan is a list of false positives rather than a shorter true one.
   *
   * EDDI refuses to purge on one — 409 `incomplete_scan` — so this is not the
   * last line of defence. It is the difference between the operator being told
   * why the list is untrustworthy and the operator clicking Purge and getting
   * an error they did not expect.
   *
   * Optional: an EDDI predating the field sends neither, and absence must read
   * as "complete" or every existing deployment would show the warning forever.
   */
  scanComplete?: boolean;
  /** Human-readable cause when `scanComplete` is false. */
  scanWarning?: string | null;
}

/** Whether this report's list can be acted on. Absent means yes. */
export function isScanComplete(report: OrphanReport | undefined): boolean {
  return report?.scanComplete !== false;
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
