import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import Keycloak from "keycloak-js";
import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getAuthConfig, type AuthConfig } from "@/lib/auth-config";
import { api } from "@/lib/api-client";
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

  const { t } = useTranslation();
  const [authenticated, setAuthenticated] = useState(false);
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [roles, setRoles] = useState<string[]>([]);
  // Keycloak could not be initialised: nothing can load without a token, so the
  // app is replaced by a sign-in prompt instead of a wall of failed requests.
  const [initFailed, setInitFailed] = useState(false);
  // The session ended mid-use (refresh token expired or revoked). Reported as a
  // banner over the still-mounted app, never an automatic logout: a redirect
  // would throw away whatever the user had typed but not saved.
  const [sessionExpired, setSessionExpired] = useState(false);

  // Preserve the ID token across token refreshes.
  // keycloak-js deletes `keycloak.idToken` when the refresh token endpoint
  // response doesn't include a new id_token (OIDC spec allows this).
  // Without id_token_hint, Keycloak 26 redirects back after logout WITHOUT
  // actually invalidating the SSO session — the user silently re-authenticates.
  const idTokenRef = useRef<string | undefined>(undefined);

  // Initialize Keycloak
  useEffect(() => {
    let mounted = true;

    const initKeycloak = async () => {
      try {
        // Renew the token. Shared by keycloak's own expiry timer and by the
        // API client, which calls it before each request (a token that expired
        // while the tab was throttled is otherwise only noticed as a 401) and
        // once more, forced, after a 401.
        const syncTokens = (refreshed: boolean) => {
          if (!mounted || !keycloak.token) return false;
          api.setAuthToken(keycloak.token);
          // Keep idTokenRef in sync — Keycloak may or may not return a
          // new id_token in the refresh response. We always preserve the
          // most recent one so logout can send id_token_hint.
          if (keycloak.idToken) {
            idTokenRef.current = keycloak.idToken;
          }
          // A refresh that works again (a transient failure, a retry) means the
          // session is alive: take the expired banner down.
          if (refreshed) setSessionExpired(false);
          return true;
        };
        const refresh = async (force: boolean) => {
          const refreshed = await keycloak.updateToken(force ? -1 : 30);
          return syncTokens(refreshed);
        };

        keycloak.onTokenExpired = () => {
          refresh(false)
            .then((ok) => {
              if (ok && import.meta.env.DEV) console.log("[EDDI Auth] Token refreshed");
            })
            .catch(() => {
              console.warn("[EDDI Auth] Token refresh failed, session expired");
              if (mounted) setSessionExpired(true);
            });
        };
        api.setTokenRefresher(refresh);
        api.setUnauthorizedHandler(() => {
          if (mounted) setSessionExpired(true);
        });

        const auth = await keycloak.init({
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

        if (!mounted) return;

        setAuthenticated(auth);
        if (!auth) setInitFailed(true);

        if (auth && keycloak.token) {
          api.setAuthToken(keycloak.token);

          // Persist the initial id_token so logout always has id_token_hint available.
          if (keycloak.idToken) {
            idTokenRef.current = keycloak.idToken;
          }

          // Load user info from OIDC /userinfo endpoint (has CORS headers).
          // loadUserProfile() uses /account which lacks CORS for cross-origin setups.
          try {
            const info = await keycloak.loadUserInfo() as Record<string, string>;
            setUser({
              username: info.preferred_username ?? (keycloak.tokenParsed?.preferred_username as string) ?? "",
              firstName: info.given_name ?? (keycloak.tokenParsed?.given_name as string) ?? "",
              lastName: info.family_name ?? (keycloak.tokenParsed?.family_name as string) ?? "",
              email: info.email ?? (keycloak.tokenParsed?.email as string) ?? "",
              fullName: info.name ?? (keycloak.tokenParsed?.name as string) ?? "",
            });
          } catch {
            // Fallback to token claims if userinfo request fails
            setUser({
              username:
                (keycloak.tokenParsed?.preferred_username as string) ?? "",
              firstName: (keycloak.tokenParsed?.given_name as string) ?? "",
              lastName: (keycloak.tokenParsed?.family_name as string) ?? "",
              email: (keycloak.tokenParsed?.email as string) ?? "",
              fullName: (keycloak.tokenParsed?.name as string) ?? "",
            });
          }

          // Extract realm roles
          const realmRoles =
            keycloak.tokenParsed?.realm_access?.roles ?? [];
          setRoles(realmRoles);
        }

        setLoading(false);
        if (import.meta.env.DEV) console.log("[EDDI Auth] Keycloak initialized, authenticated:", auth);
      } catch (error) {
        if (!mounted) return;
        console.error("[EDDI Auth] Keycloak init failed:", error);
        setLoading(false);
        setAuthenticated(false);
        setInitFailed(true);
      }
    };

    initKeycloak();

    return () => {
      mounted = false;
      api.setTokenRefresher(null);
      api.setUnauthorizedHandler(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = useCallback(() => {
    keycloak.login();
  }, [keycloak]);

  const logout = useCallback(() => {
    api.clearAuthToken();
    // Keycloak 26 only terminates the SSO session when id_token_hint is present.
    // keycloak.idToken may have been deleted by a token refresh that didn't
    // return a new id_token — restore it from the ref so the hint is always sent.
    if (!keycloak.idToken && idTokenRef.current) {
      keycloak.idToken = idTokenRef.current;
    }
    // Redirect to /manage (the SPA root), not window.location.origin (bare
    // origin = "/"), because the EDDI backend returns 401 for the root path
    // when auth is enabled — only /manage and its sub-paths serve the SPA.
    keycloak.logout({ redirectUri: `${window.location.origin}/manage` });
  }, [keycloak]);

  const contextValue = useMemo<AuthContextValue>(
    () => ({
      authenticated,
      loading,
      user,
      roles,
      method: "keycloak",
      login,
      logout,
    }),
    [authenticated, loading, user, roles, login, logout]
  );

  // Show loading screen during Keycloak init
  if (loading) {
    return (
      <div
        className="flex h-screen items-center justify-center bg-background"
        data-testid="auth-loading"
      >
        <div className="flex flex-col items-center gap-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
          <p className="text-sm text-muted-foreground">
            {t("auth.authenticating", "Authenticating…")}
          </p>
        </div>
      </div>
    );
  }

  if (initFailed) {
    return (
      <div
        className="flex h-screen items-center justify-center bg-background px-4"
        data-testid="auth-error"
      >
        <div className="flex max-w-md flex-col items-center gap-4 text-center">
          <AlertTriangle className="h-10 w-10 text-destructive" />
          <h1 className="text-lg font-semibold text-foreground">
            {t("auth.initFailedTitle", "Sign-in failed")}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t(
              "auth.initFailedBody",
              "The Manager could not complete sign-in. The login service may be unreachable or your session may have been rejected.",
            )}
          </p>
          <Button onClick={login} data-testid="auth-sign-in-again">
            {t("auth.signInAgain", "Sign in again")}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <AuthContext.Provider value={contextValue}>
      {sessionExpired && (
        <div
          role="alert"
          className="fixed inset-x-0 top-0 z-[100] flex flex-wrap items-center justify-center gap-3 border-b border-warning/40 bg-warning/15 px-4 py-2 text-sm text-foreground backdrop-blur"
          data-testid="session-expired-banner"
        >
          <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />
          <span>
            <strong>{t("auth.sessionExpiredTitle", "Your session has expired.")}</strong>{" "}
            {t(
              "auth.sessionExpiredBody",
              "Unsaved changes are still on this page — copy anything you need before signing in again.",
            )}
          </span>
          <Button size="sm" onClick={login} data-testid="auth-sign-in-again">
            {t("auth.signInAgain", "Sign in again")}
          </Button>
        </div>
      )}
      {children}
    </AuthContext.Provider>
  );
}
