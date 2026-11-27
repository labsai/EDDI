/**
 * Where the Workforce "pin this response" bookmarks live, and who may see them.
 *
 * Pinned responses are agent output (a board's advice, possibly about a
 * customer) kept in `localStorage`, which is shared by everyone who uses the
 * browser profile and survives sign-out. Two rules keep that from becoming a
 * leak between people on one machine:
 *
 * - **The key names the signed-in principal**, so a second user who signs in on
 *   the same browser reads their own list, not the previous person's.
 * - **Sign-out removes every pin key**, the previous user's included
 *   ({@link clearWorkforcePins}). Keys written before the principal was part of
 *   the name (`workforce-pins-<boardId>`) match the same prefix and go too; they
 *   are never read again, since the new key shape differs.
 */
export const WORKFORCE_PINS_PREFIX = "workforce-pins-";

/** Used where authentication is off: there is no one to separate pins between. */
const ANONYMOUS_PRINCIPAL = "anonymous";

export function pinsStorageKey(boardId: string, principal: string | null | undefined): string {
  const who = principal && principal.trim() ? principal.trim() : ANONYMOUS_PRINCIPAL;
  // `encodeURIComponent` escapes `:`, so it is an unambiguous delimiter.
  return `${WORKFORCE_PINS_PREFIX}${encodeURIComponent(who)}:${boardId}`;
}

/** Remove every pinned-response key. Safe to call when storage is unavailable. */
export function clearWorkforcePins(storage?: Storage): void {
  try {
    const store = storage ?? localStorage;
    const doomed: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i);
      if (key && key.startsWith(WORKFORCE_PINS_PREFIX)) doomed.push(key);
    }
    doomed.forEach((key) => store.removeItem(key));
  } catch {
    /* storage blocked (private mode, disabled site data): nothing was kept */
  }
}
