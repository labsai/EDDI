import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useTranslation } from "react-i18next";
import Keycloak from "keycloak-js";
import { getAuthConfig, type AuthConfig } from "@/lib/auth-config";
import { api } from "@/lib/api-client";
import {
  createTokenRefresher,
  realmRoles,
  REFRESH_INTERVAL_MS,
} from "@/lib/keycloak-session";
import { clearUserScopedStorage } from "@/lib/user-storage";
import {
  AuthContext,
  GUEST_CONTEXT,
  type AuthUser,
  type AuthContextValue,
} from "./auth-context";

interface AuthProviderProps {
  children: ReactNode;
  /** Override config for testing */
  configOverride?: AuthConfig;
}

export function AuthProvider({ children, configOverride }: AuthProviderProps) {
  const config = useMemo(
    () => configOverride ?? getAuthConfig(),
    [configOverride]
  );

  // When auth is disabled, render children immediately
  if (config.method === "none") {
    return (
      <AuthContext.Provider value={GUEST_CONTEXT}>
        {children}
      </AuthContext.Provider>
    );
  }

  return (
    <KeycloakAuthProvider config={config}>{children}</KeycloakAuthProvider>
  );
}

/** Profile fields from the OIDC userinfo response, falling back to token claims. */
function toAuthUser(
  keycloak: Keycloak,
  info: Record<string, string> = {},
): AuthUser {
  const claims = (keycloak.tokenParsed ?? {}) as Record<string, unknown>;
  const claim = (name: string) => (claims[name] as string | undefined) ?? "";
  const id = info.sub ?? claim("sub");
  return {
    ...(id ? { id } : {}),
    username: info.preferred_username ?? claim("preferred_username"),
    firstName: info.given_name ?? claim("given_name"),
    lastName: info.family_name ?? claim("family_name"),
    email: info.email ?? claim("email"),
    fullName: info.name ?? claim("name"),
  };
}

function sameRoles(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((role, i) => role === b[i]);
}

/**
 * What the provider renders while there is no usable session.
 *
 * - `loading`: Keycloak is initialising (or redirecting to sign in).
 * - `failed`: init threw — Keycloak unreachable, misconfigured realm or client.
 *   The app used to render anyway, unauthenticated, so every call 401'd with
 *   nothing on screen saying why and no way to sign in.
 * - `incomplete`: Keycloak sent the user back with an OAuth error — the user
 *   cancelled, or Keycloak refused the sign-in. The service is reachable, so
 *   "could not reach the sign-in service" would be the wrong thing to say.
 * - `signed-out`: init completed without a session.
 */
type Gate = "loading" | "failed" | "incomplete" | "signed-out" | "ready";

/**
 * keycloak-js rejects `init()` with a plain `{ error, error_description }`
 * object — not an `Error` — when the login redirect comes back with an OAuth
 * error (`access_denied` when the user cancels).
 */
function isOAuthCallbackError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    !(error instanceof Error) &&
    typeof (error as { error?: unknown }).error === "string"
  );
}

/** Internal component that handles Keycloak init lifecycle */
function KeycloakAuthProvider({
  config,
  children,
}: {
  config: AuthConfig;
  children: ReactNode;
}) {
  const [keycloak] = useState(
    () =>
      new Keycloak({
        url: config.url,
        realm: config.realm,
        clientId: config.clientId,
      })
  );

  const [gate, setGate] = useState<Gate>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);
  const [roles, setRoles] = useState<string[]>([]);

  // Preserve the ID token across token refreshes.
  // keycloak-js deletes `keycloak.idToken` when the refresh token endpoint
  // response doesn't include a new id_token (OIDC spec allows this).
  // Without id_token_hint, Keycloak 26 redirects back after logout WITHOUT
  // actually invalidating the SSO session — the user silently re-authenticates.
  const idTokenRef = useRef<string | undefined>(undefined);

  /**
   * The ONE `keycloak.init()` call for this instance.
   *
   * React StrictMode (on in `main.tsx`) runs every effect twice in development.
   * keycloak-js throws "A 'Keycloak' instance can only be initialized once" on
   * the second call, and the first call's result was discarded because its
   * effect had already been cleaned up — so `npm run dev` with Keycloak could
   * never sign in. Both effect runs now await the same promise. A ref, not
   * module state: it survives StrictMode's simulated remount but belongs to this
   * Keycloak instance.
   */
  const initRef = useRef<Promise<boolean> | null>(null);

  /** Publish the current token and refresh everything derived from it. */
  const applyToken = useCallback(() => {
    if (!keycloak.token) return;
    api.setAuthToken(keycloak.token);
    // Keep idTokenRef in sync — Keycloak may or may not return a new id_token
    // in the refresh response. We always preserve the most recent one so
    // logout can send id_token_hint.
    if (keycloak.idToken) idTokenRef.current = keycloak.idToken;
    // Roles live in the token, so a role granted or revoked in Keycloak takes
    // effect at the next refresh instead of at the next full sign-in.
    const next = realmRoles(keycloak);
    setRoles((prev) => (sameRoles(prev, next) ? prev : next));
  }, [keycloak]);

  // Initialize Keycloak
  useEffect(() => {
    let mounted = true;

    const initKeycloak = async () => {
      try {
        initRef.current ??= keycloak.init({
          onLoad: "login-required",
          checkLoginIframe: false,
          pkceMethod: "S256",
          // Use query params (not hash fragment) for the auth code redirect.
          // This avoids hash-parsing issues in browsers with stale session data
          // and makes the 400-without-CORS-headers error easier to diagnose
          // (the browser can read the actual 400 response body instead of
          // seeing a generic "TypeError: Failed to fetch").
          responseMode: "query",
        });
        const auth = await initRef.current;

        if (!mounted) return;

        if (!auth || !keycloak.token) {
          setGate("signed-out");
          return;
        }

        applyToken();

        // Load user info from OIDC /userinfo endpoint (has CORS headers).
        // loadUserProfile() uses /account which lacks CORS for cross-origin setups.
        let profile: AuthUser;
        try {
          const info = (await keycloak.loadUserInfo()) as Record<string, string>;
          profile = toAuthUser(keycloak, info);
        } catch {
          // Fallback to token claims if userinfo request fails
          profile = toAuthUser(keycloak);
        }
        if (!mounted) return;
        setUser(profile);
        setGate("ready");
        if (import.meta.env.DEV) console.log("[EDDI Auth] Keycloak initialized");
      } catch (error) {
        if (!mounted) return;
        console.error("[EDDI Auth] Keycloak init failed:", error);
        setGate(isOAuthCallbackError(error) ? "incomplete" : "failed");
      }
    };

    void initKeycloak();

    return () => {
      mounted = false;
    };
  }, [keycloak, applyToken]);

  // Keep the token fresh for as long as the session is live.
  useEffect(() => {
    if (gate !== "ready") return;

    // A refresh that failed because the session is GONE (the token endpoint
    // rejected the refresh token). No login() here: keycloak-js's clearToken()
    // already redirects to sign in, because init ran with
    // `onLoad: "login-required"` (which sets `loginRequired`) — a second call
    // only assigned `location` twice. What is left for us is to stop sending a
    // dead token in the moment before the redirect. A failure that left the
    // session intact (network error, Keycloak 5xx) is retried on the next tick
    // instead of logging the user out.
    const onSessionLost = () => {
      api.clearAuthToken();
    };

    const refresher = createTokenRefresher(keycloak, applyToken, onSessionLost);
    api.setTokenRefresher(refresher);

    keycloak.onTokenExpired = () => {
      void refresher.ensureFresh();
    };
    const timer = window.setInterval(() => {
      void refresher.ensureFresh();
    }, REFRESH_INTERVAL_MS);

    // A laptop waking from sleep skips every interval tick it slept through;
    // refresh as soon as the tab is looked at again.
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresher.ensureFresh();
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      keycloak.onTokenExpired = undefined;
      api.setTokenRefresher(null);
    };
  }, [gate, keycloak, applyToken]);

  const login = useCallback(() => {
    void keycloak.login();
  }, [keycloak]);

  const logout = useCallback(() => {
    api.setTokenRefresher(null);
    api.clearAuthToken();
    // Browser-held data belonging to this user (see `user-storage.ts`) must not
    // be there for whoever signs in next on this machine.
    clearUserScopedStorage();
    // Keycloak 26 only terminates the SSO session when id_token_hint is present.
    // keycloak.idToken may have been deleted by a token refresh that didn't
    // return a new id_token — restore it from the ref so the hint is always sent.
    if (!keycloak.idToken && idTokenRef.current) {
      keycloak.idToken = idTokenRef.current;
    }
    // Redirect to /manage (the SPA root), not window.location.origin (bare
    // origin = "/"), because the EDDI backend returns 401 for the root path
    // when auth is enabled — only /manage and its sub-paths serve the SPA.
    void keycloak.logout({ redirectUri: `${window.location.origin}/manage` });
  }, [keycloak]);

  const contextValue = useMemo<AuthContextValue>(
    () => ({
      authenticated: gate === "ready",
      loading: gate === "loading",
      user,
      roles,
      method: "keycloak",
      login,
      logout,
    }),
    [gate, user, roles, login, logout]
  );

  if (gate !== "ready") {
    return <AuthGateScreen gate={gate} onSignIn={login} />;
  }

  return (
    <AuthContext.Provider value={contextValue}>{children}</AuthContext.Provider>
  );
}

/**
 * Full-screen state shown instead of the app until a session exists.
 *
 * Rendered outside `ThemeProvider` and every other app provider, so it uses
 * only tokens that resolve without them.
 */
function AuthGateScreen({ gate, onSignIn }: { gate: Gate; onSignIn: () => void }) {
  const { t } = useTranslation();

  if (gate === "loading") {
    return (
      <div
        className="flex h-screen items-center justify-center bg-background"
        data-testid="auth-loading"
        role="status"
      >
        <div className="flex flex-col items-center gap-4">
          <div
            className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary"
            aria-hidden="true"
          />
          <p className="text-sm text-muted-foreground">
            {t("auth.loading", "Authenticating…")}
          </p>
        </div>
      </div>
    );
  }

  const failed = gate === "failed";
  const incomplete = gate === "incomplete";
  return (
    <div
      className="flex h-screen items-center justify-center bg-background p-4"
      data-testid={
        failed ? "auth-init-failed" : incomplete ? "auth-incomplete" : "auth-signed-out"
      }
      role="alert"
    >
      <div className="flex max-w-sm flex-col items-center gap-4 text-center">
        <h1 className="text-lg font-semibold text-foreground">
          {failed
            ? t("auth.initFailedTitle", "Sign-in is unavailable")
            : incomplete
              ? t("auth.incompleteTitle", "Sign-in did not complete")
              : t("auth.signedOutTitle", "You are signed out")}
        </h1>
        <p className="text-sm text-muted-foreground">
          {failed
            ? t(
                "auth.initFailedMessage",
                "The Manager could not reach the sign-in service. Check your connection, then try again.",
              )
            : incomplete
              ? t(
                  "auth.incompleteMessage",
                  "The sign-in was cancelled or refused. Sign in again to continue.",
                )
              : t("auth.signedOutMessage", "Sign in to continue.")}
        </p>
        {/* A failed init leaves the adapter unusable, so the way out is a fresh
            page load (which re-runs init), not login() on the broken instance. */}
        <button
          type="button"
          onClick={failed ? () => window.location.reload() : onSignIn}
          data-testid={failed ? "auth-retry" : "auth-sign-in"}
          className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
        >
          {failed ? t("auth.retry", "Try again") : t("auth.signIn", "Sign in")}
        </button>
      </div>
    </div>
  );
}
