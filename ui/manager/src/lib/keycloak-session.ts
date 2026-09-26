import type { TokenRefresher } from "@/lib/api-client";

/**
 * The slice of a `keycloak-js` instance the session logic uses. Structural, so
 * tests can drive it with a plain object instead of a live Keycloak.
 */
export interface KeycloakLike {
  token?: string;
  refreshToken?: string;
  tokenParsed?: { realm_access?: { roles?: string[] } };
  updateToken(minValidity: number): Promise<boolean>;
}

/**
 * Refresh when the token has less than this many seconds left.
 *
 * Refreshing only on `onTokenExpired` (as the provider used to) left a window
 * at every expiry in which requests went out with a dead token. Refreshing
 * ahead of time closes it.
 */
export const REFRESH_MIN_VALIDITY_SECONDS = 60;

/** How often the background check runs. Well under the minimum validity. */
export const REFRESH_INTERVAL_MS = 20_000;

/** The realm roles carried by the current token. */
export function realmRoles(keycloak: KeycloakLike): string[] {
  return keycloak.tokenParsed?.realm_access?.roles ?? [];
}

/**
 * Whether a failed refresh ended the session, as opposed to merely failing.
 *
 * `keycloak-js` clears its tokens only when the token endpoint REJECTS the
 * refresh token (HTTP 400: expired or revoked session). A network error, a
 * proxy timeout or a Keycloak 5xx leaves them in place, and the next attempt
 * may well succeed. The provider used to log out on any failure, so one
 * dropped request signed the user out mid-edit.
 */
export function sessionLost(keycloak: KeycloakLike): boolean {
  return !keycloak.refreshToken;
}

/**
 * The `ApiClient` side of a Keycloak session.
 *
 * `onRefreshed` runs after every successful refresh — the provider uses it to
 * publish the new token and re-read roles. `onSessionLost` runs when a refresh
 * failure means the session is gone (see {@link sessionLost}).
 */
export function createTokenRefresher(
  keycloak: KeycloakLike,
  onRefreshed: () => void,
  onSessionLost: () => void,
): TokenRefresher {
  async function refresh(minValidity: number): Promise<boolean> {
    try {
      const refreshed = await keycloak.updateToken(minValidity);
      if (refreshed) onRefreshed();
      return refreshed;
    } catch {
      if (sessionLost(keycloak)) onSessionLost();
      return false;
    }
  }

  return {
    async ensureFresh() {
      // Nothing to refresh with (not signed in yet, or already lost): send the
      // request as it is and let the server answer.
      if (!keycloak.refreshToken) return;
      await refresh(REFRESH_MIN_VALIDITY_SECONDS);
    },
    async forceRefresh() {
      if (!keycloak.refreshToken) return false;
      // -1 is keycloak-js's "refresh regardless of remaining validity".
      return refresh(-1);
    },
  };
}
