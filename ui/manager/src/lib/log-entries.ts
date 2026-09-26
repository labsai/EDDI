import type { DatabaseLogEntry, LogEntry } from "@/lib/api/logs";

/**
 * Identity of a log line, for de-duplication and React keys.
 *
 * The backend replays up to 50 ring-buffer entries at the start of EVERY
 * `/administration/logs/stream` connection (`RestLogAdmin.streamLogs`), and the
 * REST seed overlaps the stream as well — so the same line routinely arrives
 * two or three times. The payload carries no id, so identity is the fields that
 * together describe one emitted line.
 */
export function logEntryKey(e: LogEntry): string {
  return [
    e.timestamp,
    e.instanceId ?? "",
    e.level ?? "",
    e.loggerName ?? "",
    e.conversationId ?? "",
    e.message ?? "",
  ].join("\u0001");
}

/**
 * Merge `incoming` into `existing`, dropping lines already present, and return
 * the result newest-first capped at `max`.
 *
 * Arrival order is NOT time order: a reconnect replays older entries after
 * newer ones have been shown, and the REST seed lands whenever it lands. The
 * common case — one new live line that is newer than everything shown — takes a
 * cheap prepend; anything else is sorted by timestamp (stable, so equal
 * timestamps keep their arrival order).
 */
export function mergeNewestFirst(
  existing: LogEntry[],
  incoming: LogEntry[],
  max: number
): LogEntry[] {
  if (incoming.length === 0) return existing;
  const seen = new Set(existing.map(logEntryKey));
  const fresh: LogEntry[] = [];
  for (const e of incoming) {
    const k = logEntryKey(e);
    if (seen.has(k)) continue;
    seen.add(k);
    fresh.push(e);
  }
  if (fresh.length === 0) return existing;

  const head = existing[0];
  let merged: LogEntry[];
  if (
    fresh.length === 1 &&
    (head === undefined || fresh[0]!.timestamp >= head.timestamp)
  ) {
    merged = [fresh[0]!, ...existing];
  } else {
    merged = [...fresh, ...existing].sort((a, b) => b.timestamp - a.timestamp);
  }
  return merged.length > max ? merged.slice(0, max) : merged;
}

/** Identity of a history row, for de-duplication across pages and React keys. */
export function historyEntryKey(e: DatabaseLogEntry): string {
  return [
    e.timestamp ?? "",
    e.instanceId ?? "",
    e.level ?? "",
    e.loggerName ?? "",
    e.conversationId ?? "",
    e.message ?? "",
  ].join("\u0001");
}
