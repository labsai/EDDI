import { isApiError } from "@/lib/api-client";
import type { AdminAction } from "@/lib/api/audit";

/** The mutating methods `AdminActionAuditFilter` records. */
export const ADMIN_ACTION_METHODS = ["POST", "PUT", "PATCH", "DELETE"] as const;

/** The time windows offered by the filter, in milliseconds; `null` is "any time". */
export const ADMIN_ACTION_WINDOWS = {
  any: null,
  hour: 60 * 60 * 1000,
  day: 24 * 60 * 60 * 1000,
  week: 7 * 24 * 60 * 60 * 1000,
  month: 30 * 24 * 60 * 60 * 1000,
} as const;

export type AdminActionWindow = keyof typeof ADMIN_ACTION_WINDOWS;

export interface AdminActionFilter {
  /** `""` for every method. */
  method: string;
  window: AdminActionWindow;
}

/**
 * The method and time filters, applied to the rows loaded so far.
 *
 * The endpoint filters by actor only; method and time are narrowed here. The
 * list is newest first, so everything older than the window's start is past the
 * end of the window — a row with a timestamp that does not parse is kept rather
 * than silently hidden.
 */
export function filterAdminActions(
  actions: AdminAction[],
  filter: AdminActionFilter,
  now: number = Date.now(),
): AdminAction[] {
  const span = ADMIN_ACTION_WINDOWS[filter.window];
  const since = span == null ? null : now - span;
  return actions.filter((action) => {
    if (filter.method && action.method !== filter.method) return false;
    if (since != null) {
      const at = Date.parse(action.timestamp);
      if (!Number.isNaN(at) && at < since) return false;
    }
    return true;
  });
}

/**
 * Whether older pages could still hold rows inside the time window. Newest
 * first, so once the oldest loaded row is older than the window, nothing further
 * back can match.
 */
export function olderPagesCanMatch(
  actions: AdminAction[],
  window: AdminActionWindow,
  now: number = Date.now(),
): boolean {
  const span = ADMIN_ACTION_WINDOWS[window];
  if (span == null || actions.length === 0) return true;
  const oldest = Date.parse(actions[actions.length - 1]!.timestamp);
  return Number.isNaN(oldest) || oldest >= now - span;
}

/** Why the list could not be read, in the terms the page explains it. */
export type AdminActionsFailure = "forbidden" | "unsupported" | "error";

export function adminActionsFailure(error: unknown): AdminActionsFailure {
  if (isApiError(error)) {
    if (error.status === 401 || error.status === 403) return "forbidden";
    // An EDDI older than 6.6 has no such endpoint; the path then falls through
    // to `/auditstore/{conversationId}`, which answers 404 as well.
    if (error.status === 404 || error.status === 405) return "unsupported";
  }
  return "error";
}

/** A Tailwind token class for an HTTP status. */
export function statusTone(status: number | null): "ok" | "refused" | "failed" | "unknown" {
  if (status == null) return "unknown";
  if (status >= 500) return "failed";
  if (status >= 400) return "refused";
  return "ok";
}
