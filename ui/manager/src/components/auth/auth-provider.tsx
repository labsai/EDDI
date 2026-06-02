import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import Keycloak from "keycloak-js";
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

  const [authenticated, setAuthenticated] = useState(false);
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [roles, setRoles] = useState<string[]>([]);

  // Initialize Keycloak
  useEffect(() => {
    let mounted = true;

    const initKeycloak = async () => {
      try {
        // Set up token refresh before init
        keycloak.onTokenExpired = () => {
          keycloak
            .updateToken(30)
            .then(() => {
              if (mounted && keycloak.token) {
                api.setAuthToken(keycloak.token);
                if (import.meta.env.DEV) console.log("[EDDI Auth] Token refreshed");
              }
            })
            .catch(() => {
              console.warn("[EDDI Auth] Token refresh failed, logging out");
              keycloak.logout();
            });
        };

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

        if (auth && keycloak.token) {
          api.setAuthToken(keycloak.token);

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
      }
    };

    initKeycloak();

    return () => {
      mounted = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = useCallback(() => {
    keycloak.login();
  }, [keycloak]);

  const logout = useCallback(() => {
    api.clearAuthToken();
    keycloak.logout({ redirectUri: window.location.origin });
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
          <p className="text-sm text-muted-foreground">Authenticating…</p>
        </div>
      </div>
    );
  }

  return (
    <AuthContext.Provider value={contextValue}>{children}</AuthContext.Provider>
  );
}
