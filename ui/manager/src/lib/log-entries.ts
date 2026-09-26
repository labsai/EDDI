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
 * The entries of `incoming` that are not already in `existing`, counted by
 * MULTIPLICITY rather than by identity.
 *
 * A key is not unique: a retry loop or a burst of the same warning emits
 * identical lines in one millisecond. Dropping every incoming line whose key
 * was already seen collapsed those genuine repeats into one — including inside
 * a single REST seed. What overlaps is a replay of lines already shown, so an
 * incoming line is admitted only once `incoming` holds more copies of its key
 * than `existing` does.
 *
 * One case stays indistinguishable without a sequence id from the backend: a
 * single live line that is an exact twin of a line already shown.
 */
export function newByMultiplicity<T>(
  existing: T[],
  incoming: T[],
  key: (e: T) => string
): T[] {
  const have = new Map<string, number>();
  for (const e of existing) {
    const k = key(e);
    have.set(k, (have.get(k) ?? 0) + 1);
  }
  const seenIncoming = new Map<string, number>();
  const fresh: T[] = [];
  for (const e of incoming) {
    const k = key(e);
    const n = (seenIncoming.get(k) ?? 0) + 1;
    seenIncoming.set(k, n);
    if (n > (have.get(k) ?? 0)) fresh.push(e);
  }
  return fresh;
}

/**
 * Merge `incoming` into `existing`, dropping lines already present (see
 * {@link newByMultiplicity}), and return the result newest-first capped at
 * `max`.
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
  const fresh = newByMultiplicity(existing, incoming, logEntryKey);
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

/**
 * React keys for a list rendered OLDEST first: the line's identity plus its
 * occurrence number among identical lines. Genuine repeats get distinct keys,
 * and appending newer lines never changes an earlier line's key, so the list
 * does not remount as it grows.
 */
export function keyedOldestFirst<T>(
  items: T[],
  key: (e: T) => string
): Array<{ item: T; key: string }> {
  const seen = new Map<string, number>();
  return items.map((item) => {
    const k = key(item);
    const n = seen.get(k) ?? 0;
    seen.set(k, n + 1);
    return { item, key: n === 0 ? k : `${k}#${n}` };
  });
}

