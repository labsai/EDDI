/**
 * localStorage keys that hold one signed-in user's data.
 *
 * Several Manager features keep per-user state in the browser (Workforce's
 * advisor threads, saved discussion templates). They used one global key each,
 * so on a shared machine the next person to sign in saw — and could reopen —
 * the previous person's threads, and nothing removed them at logout.
 *
 * Keys are now suffixed with the user id whenever someone is signed in. With
 * auth disabled there is no user and the plain key is used, exactly as before.
 */

/** The storage key for `base`, scoped to `userId` when there is one. */
export function userScopedKey(base: string, userId: string | null | undefined): string {
  return userId ? `${base}:${userId}` : base;
}

/**
 * Keys wiped on logout, in every scoping.
 *
 * Only data that is a cache of server state belongs here. Advisor threads are
 * pointers to conversations that still exist on the server, so dropping them
 * loses nothing. Saved discussion templates are NOT listed: they exist only in
 * this browser, deleting them at logout would destroy the user's work, and the
 * per-user key already keeps them from the next person.
 */
export const CLEARED_ON_LOGOUT: readonly string[] = ["workforce-threads"];

/** Remove every user-scoped copy of the {@link CLEARED_ON_LOGOUT} keys. */
export function clearUserScopedStorage(): void {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && CLEARED_ON_LOGOUT.some((base) => key === base || key.startsWith(`${base}:`))) {
        doomed.push(key);
      }
    }
    for (const key of doomed) localStorage.removeItem(key);
  } catch {
    // Storage unavailable — there is nothing stored to clear.
  }
}
