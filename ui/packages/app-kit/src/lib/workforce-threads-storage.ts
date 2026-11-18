/**
 * Where Workforce remembers each advisor's conversation, and whose it is.
 *
 * The thread page keeps a pointer per (board, advisor) to the conversation it
 * resumes, in `localStorage`, which everyone who uses the browser profile shares
 * and which survives sign-out. One key for all of them meant the next person to
 * sign in found the previous person's pointers and resumed THEIR conversation.
 * The same two rules as the pins (`workforce-pins.ts`) now hold here:
 *
 * - **The key names the signed-in principal**, so a second user reads their own.
 * - **Sign-out removes the signing-out person's key**
 *   ({@link clearWorkforceThreads}), and the unscoped key older versions wrote.
 */

/** The key older versions wrote for everybody; never read again, removed on sight. */
export const LEGACY_THREADS_KEY = "workforce-threads";

export const WORKFORCE_THREADS_PREFIX = "workforce-threads-";

/** Used where authentication is off: there is no one to separate threads between. */
const ANONYMOUS_PRINCIPAL = "anonymous";

/**
 * Who per-person client state belongs to: the token's stable subject, the
 * username only when a token carries none. (A username can be renamed or reused;
 * the subject cannot.)
 */
export function principalOf(user: { sub?: string; username?: string } | null | undefined): string | null {
  const sub = user?.sub?.trim();
  if (sub) return sub;
  const name = user?.username?.trim();
  return name || null;
}

export function threadsStorageKey(principal: string | null | undefined): string {
  const who = principal && principal.trim() ? principal.trim() : ANONYMOUS_PRINCIPAL;
  return `${WORKFORCE_THREADS_PREFIX}${encodeURIComponent(who)}`;
}

/**
 * Remove the signing-out person's thread pointers and the unscoped key older
 * versions wrote. Another person's keys stay: their pointers are theirs, and on a
 * shared profile they find their conversations again at their next sign-in.
 * Safe to call when storage is unavailable.
 */
export function clearWorkforceThreads(principal: string | null | undefined, storage?: Storage): void {
  try {
    const store = storage ?? localStorage;
    store.removeItem(threadsStorageKey(principal));
    store.removeItem(LEGACY_THREADS_KEY);
  } catch {
    /* storage blocked (private mode, disabled site data): nothing was kept */
  }
}
