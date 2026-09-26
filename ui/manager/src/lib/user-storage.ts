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

/** The id to scope storage by: the stable OIDC subject, else the username. */
export function storageUserId(
  user: { id?: string; username?: string } | null | undefined,
): string | undefined {
  return user?.id || user?.username || undefined;
}

/**
 * Read `base` for `userId`, adopting pre-upgrade data on first use.
 *
 * Before these keys were scoped, everything lived under the plain `base` key.
 * Switching keys hid every template a user had saved. So the first time a
 * signed-in user's own key is empty while the plain key holds data, that data
 * is MOVED into their key: it becomes theirs, exactly as reachable as it was
 * before the upgrade (it was visible to whoever used the browser), and it moves
 * once — the next user to sign in does not inherit it too. Nothing is deleted.
 *
 * Returns the raw stored string, or null.
 */
export function readUserScoped(base: string, userId: string | null | undefined): string | null {
  try {
    const key = userScopedKey(base, userId);
    const own = localStorage.getItem(key);
    if (own !== null || !userId) return own;
    const legacy = localStorage.getItem(base);
    if (legacy === null) return null;
    localStorage.setItem(key, legacy);
    localStorage.removeItem(base);
    return legacy;
  } catch {
    return null;
  }
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
