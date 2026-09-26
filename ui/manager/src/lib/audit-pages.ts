import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";

/** Entries per request. The backend clamps a read page to 1,000. */
export const AUDIT_PAGE_SIZE = 500;
/**
 * Upper bound on pages fetched for one conversation, so a pathological
 * conversation cannot pull an unbounded amount of prompt text into the tab.
 * Reaching it is reported (`complete: false`), never hidden.
 */
export const AUDIT_MAX_PAGES = 20;

export interface AuditTrailResult {
  entries: AuditEntry[];
  /** False when the page cap stopped the walk before the trail ended. */
  complete: boolean;
}

/**
 * Every audit entry of a conversation, walking `skip` until a short page.
 *
 * The debugger used to ask for one page of 200. The backend returns entries
 * NEWEST first, so on any conversation longer than that page the cost
 * dashboard's "Total cost" summed only the latest turns while presenting the
 * figure as the conversation's total, and older turns vanished from the
 * pipeline trace. A single page cannot answer either question.
 */
export async function getWholeAuditTrail(
  conversationId: string,
  pageSize = AUDIT_PAGE_SIZE,
  maxPages = AUDIT_MAX_PAGES
): Promise<AuditTrailResult> {
  const entries: AuditEntry[] = [];
  for (let page = 0; page < maxPages; page++) {
    const batch = await getAuditTrail(conversationId, page * pageSize, pageSize);
    entries.push(...batch);
    if (batch.length < pageSize) return { entries, complete: true };
  }
  return { entries, complete: false };
}
