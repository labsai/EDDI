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
 * - **Sign-out removes every thread key**, the previous user's included
 *   ({@link clearWorkforceThreads}), and the unscoped key older versions wrote.
 */

/** The key older versions wrote for everybody; never read again, removed on sight. */
export const LEGACY_THREADS_KEY = "workforce-threads";

export const WORKFORCE_THREADS_PREFIX = "workforce-threads-";

/** Used where authentication is off: there is no one to separate threads between. */
const ANONYMOUS_PRINCIPAL = "anonymous";

export function threadsStorageKey(principal: string | null | undefined): string {
  const who = principal && principal.trim() ? principal.trim() : ANONYMOUS_PRINCIPAL;
  return `${WORKFORCE_THREADS_PREFIX}${encodeURIComponent(who)}`;
}

/** Remove every thread-pointer key. Safe to call when storage is unavailable. */
export function clearWorkforceThreads(storage?: Storage): void {
  try {
    const store = storage ?? localStorage;
    const doomed: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i);
      if (key && (key === LEGACY_THREADS_KEY || key.startsWith(WORKFORCE_THREADS_PREFIX))) doomed.push(key);
    }
    doomed.forEach((key) => store.removeItem(key));
  } catch {
    /* storage blocked (private mode, disabled site data): nothing was kept */
  }
}
