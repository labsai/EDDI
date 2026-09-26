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
  let entries: AuditEntry[] = [];
  for (let page = 0; page < maxPages; page++) {
    const batch = await getAuditTrail(conversationId, page * pageSize, pageSize);
    entries = mergeById(entries, batch);
    if (batch.length < pageSize) return { entries, complete: true };
  }
  return { entries, complete: false };
}

/**
 * Append `incoming` to `existing`, skipping ids already present.
 *
 * The ledger is sorted by timestamp alone, with no tie-breaker, and the tasks
 * of one turn routinely share a millisecond — so offset paging can return the
 * same entry on both sides of a page boundary. Summing costs over that would
 * count it twice.
 */
export function mergeById(
  existing: AuditEntry[],
  incoming: AuditEntry[]
): AuditEntry[] {
  const seen = new Set(existing.map((e) => e.id));
  const added = incoming.filter((e) => {
    if (e.id == null) return true;
    if (seen.has(e.id)) return false;
    seen.add(e.id);
    return true;
  });
  return added.length === 0 ? existing : [...existing, ...added];
}

/**
 * Bring a previously loaded trail up to date by reading only the NEWEST page.
 *
 * After each turn the debugger used to re-walk the whole trail — up to 10,000
 * entries of full prompts and responses — twice, once per consumer. The new
 * entries of a turn are at the head of the newest-first listing, so one page
 * normally covers them. If that page shares no id with what is loaded, more
 * than a page was written in between and the whole trail is re-read.
 */
export async function refreshAuditTrail(
  conversationId: string,
  previous: AuditTrailResult,
  pageSize = AUDIT_PAGE_SIZE
): Promise<AuditTrailResult> {
  const newest = await getAuditTrail(conversationId, 0, pageSize);
  const known = new Set(previous.entries.map((e) => e.id));
  const overlaps = newest.some((e) => known.has(e.id));
  if (!overlaps && newest.length >= pageSize) {
    return getWholeAuditTrail(conversationId, pageSize);
  }
  return {
    entries: mergeById(previous.entries, newest),
    complete: previous.complete,
  };
}
