import {
  isReservedMemoryKey,
  type MemoryVisibility,
  type UserMemoryEntry,
} from "@eddi/app-kit/lib/api/user-memory";

/**
 * Shaping for "What I remember about you" (T2.4): what the person sees of their
 * own memory entries. Pure, so the page's rules are tested without rendering.
 */

/** One entry as the page shows it. */
export interface MemoryRow {
  entry: UserMemoryEntry;
  /** The entry's id; an entry without one cannot be deleted on its own. */
  id: string | null;
  visibility: MemoryVisibility | "unknown";
  /** The writing agent's display name, its id when the catalog does not name it, or null. */
  agentName: string | null;
  /** For `group` entries: the teams, by name where known. */
  teamNames: string[];
  /** The value as plain text — rendered as a text child, never markdown or HTML. */
  valueText: string;
  /** Newest change, for sorting and the "updated" line (ms, 0 when unknown). */
  updatedMs: number;
}

/** The longest value shown in full; the rest is cut with an ellipsis. */
export const MAX_VALUE_CHARS = 500;

export function memoryVisibility(raw: string | null | undefined): MemoryRow["visibility"] {
  const v = (raw ?? "").toLowerCase();
  return v === "self" || v === "group" || v === "global" ? v : "unknown";
}

/** A stored value as text: strings as they are, anything else as compact JSON. */
export function memoryValueText(value: unknown): string {
  let text: string;
  if (value === null || value === undefined) text = "";
  else if (typeof value === "string") text = value;
  else {
    try {
      text = JSON.stringify(value) ?? String(value);
    } catch {
      text = String(value);
    }
  }
  return text.length > MAX_VALUE_CHARS ? `${text.slice(0, MAX_VALUE_CHARS)}…` : text;
}

function timeMs(iso: string | undefined): number {
  if (!iso) return 0;
  const ms = Date.parse(iso);
  return Number.isFinite(ms) ? ms : 0;
}

/**
 * The person's entries as rows, newest first, and how many reserved GDPR rows
 * were left out (not memories; they cannot be deleted here — see
 * `isReservedMemoryKey`).
 */
export function toMemoryRows(
  entries: readonly UserMemoryEntry[],
  agentNames: ReadonlyMap<string, string>,
  teamNames: ReadonlyMap<string, string>,
): { rows: MemoryRow[]; reservedCount: number } {
  let reservedCount = 0;
  const rows: MemoryRow[] = [];
  for (const entry of entries) {
    if (isReservedMemoryKey(entry.key)) {
      reservedCount++;
      continue;
    }
    const agentId = entry.sourceAgentId?.trim() || null;
    rows.push({
      entry,
      id: entry.id ?? null,
      visibility: memoryVisibility(entry.visibility),
      agentName: agentId ? agentNames.get(agentId) || agentId : null,
      teamNames: (entry.groupIds ?? []).map((g) => teamNames.get(g) || g),
      valueText: memoryValueText(entry.value),
      updatedMs: timeMs(entry.updatedAt) || timeMs(entry.createdAt),
    });
  }
  rows.sort((a, b) => b.updatedMs - a.updatedMs);
  return { rows, reservedCount };
}

/** Search over what the person sees: the name, the value, the agent, the category and the teams. */
export function filterMemoryRows(rows: readonly MemoryRow[], query: string): MemoryRow[] {
  const q = query.trim().toLocaleLowerCase();
  if (!q) return [...rows];
  return rows.filter((row) =>
    [row.entry.key, row.valueText, row.agentName ?? "", row.entry.category ?? "", ...row.teamNames]
      .join("\n")
      .toLocaleLowerCase()
      .includes(q),
  );
}

/** A memory key as words: `preferred_language` → `preferred language`. */
export function memoryKeyLabel(key: string): string {
  return key.replace(/[_-]+/g, " ").trim() || key;
}
