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
                console.log("[EDDI Auth] Token refreshed");
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
        });

        if (!mounted) return;

        setAuthenticated(auth);

        if (auth && keycloak.token) {
          api.setAuthToken(keycloak.token);

          // Load user profile
          try {
            const profile = await keycloak.loadUserProfile();
            setUser({
              username: profile.username ?? "",
              firstName: profile.firstName ?? "",
              lastName: profile.lastName ?? "",
              email: profile.email ?? "",
              fullName: [profile.firstName, profile.lastName]
                .filter(Boolean)
                .join(" "),
            });
          } catch {
            // Profile load failed — use token claims
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
        console.log("[EDDI Auth] Keycloak initialized, authenticated:", auth);
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
