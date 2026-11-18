import { api } from "@eddi/app-kit/lib/api-client";
import { seg } from "@eddi/app-kit/lib/api/path-segment";

/**
 * One row of an email integration's mail log
 * (`GET /channelstore/channels/{id}/email-log`): what happened to one message
 * and why. The backend never returns a subject or a body — the ledger holds
 * neither.
 */
export interface EmailLogRow {
  id: string;
  /** `TRIGGER` (an inbound mail, either mode), `REPLY`, `NOTIFY` or `NOTICE`. */
  kind: string;
  direction: "IN" | "OUT";
  messageId: string;
  state: string;
  /** The refusal or last error, e.g. `blocked:not_allowlisted`; null when there is none. */
  reason: string | null;
  /** `reason` up to its first `:`. */
  reasonCategory: string | null;
  conversationId: string | null;
  /** The sender of an inbound mail, the recipient of an outbound one. */
  counterpart: string | null;
  attempts: number;
  createdAt: string;
  updatedAt: string | null;
}

export interface EmailLogPage {
  rows: EmailLogRow[];
  /** Pass as `after` for the next page; null on the last one. */
  nextCursor: string | null;
}

export interface EmailLogFilters {
  direction?: "" | "IN" | "OUT";
  state?: string;
  /** A reason prefix, usually a category such as `blocked`. */
  reason?: string;
}

/** States a row of the mail log can be in, for the filter. */
export const EMAIL_LOG_STATES = ["DONE", "REFUSED", "RECEIVED", "CLAIMED", "TURN_SUBMITTED", "SENT", "FAILED", "SUPPRESSED", "PENDING"] as const;

/** Reason categories the backend writes (`docs/email-integration.md` → Troubleshooting). */
export const EMAIL_LOG_REASON_CATEGORIES = [
  "blocked",
  "unauthenticated",
  "loop",
  "duplicate",
  "unroutable",
  "too_large",
  "parse_error",
  "failed",
  "stranded",
  "budget",
  "throttled",
  "quota",
] as const;

export const EMAIL_LOG_PAGE_SIZE = 50;

export function getEmailLog(id: string, filters: EmailLogFilters, after?: string | null): Promise<EmailLogPage> {
  const params = new URLSearchParams({ limit: String(EMAIL_LOG_PAGE_SIZE) });
  if (filters.direction) params.set("direction", filters.direction);
  if (filters.state) params.set("state", filters.state);
  if (filters.reason) params.set("reason", filters.reason);
  if (after) params.set("after", after);
  return api.get<EmailLogPage>(`/channelstore/channels/${seg(id)}/email-log?${params.toString()}`);
}

/**
 * The rows of one Message-ID. A POST, so the id (which usually names a domain)
 * stays out of access logs; a 404 means the ledger has no such message.
 */
export function lookupEmailLog(id: string, messageId: string): Promise<EmailLogRow[]> {
  return api.post<EmailLogRow[]>(`/channelstore/channels/${seg(id)}/email-log/lookup`, { messageId });
}
