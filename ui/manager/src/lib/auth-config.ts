/**
 * Auth configuration — determines whether Keycloak auth is enabled.
 *
 * Priority:
 *  1. Vite env vars (VITE_AUTH_METHOD, VITE_AUTH_URL, etc.)
 *  2. Runtime window global (window.__EDDI_AUTH__, set by server/docker)
 *  3. Default: 'none' (no auth, app works without login)
 */

export interface AuthConfig {
  method: "keycloak" | "none";
  url: string;
  realm: string;
  clientId: string;
}

interface EddiAuthGlobal {
  method?: string;
  url?: string;
  realm?: string;
  clientId?: string;
}

declare global {
  interface Window {
    __EDDI_AUTH__?: EddiAuthGlobal;
  }
}

const DEFAULTS = {
  url: "http://localhost:8180",
  realm: "eddi",
  clientId: "eddi-manager",
} as const;

export function getAuthConfig(): AuthConfig {
  // 1. Vite env vars
  const envMethod = import.meta.env.VITE_AUTH_METHOD;
  if (envMethod === "keycloak") {
    return {
      method: "keycloak",
      url: import.meta.env.VITE_AUTH_URL || DEFAULTS.url,
      realm: import.meta.env.VITE_AUTH_REALM || DEFAULTS.realm,
      clientId: import.meta.env.VITE_AUTH_CLIENT_ID || DEFAULTS.clientId,
    };
  }

  // 2. Runtime global (injected by docker/server)
  const globalAuth = window.__EDDI_AUTH__;
  if (globalAuth?.method === "keycloak") {
    return {
      method: "keycloak",
      url: globalAuth.url || DEFAULTS.url,
      realm: globalAuth.realm || DEFAULTS.realm,
      clientId: globalAuth.clientId || DEFAULTS.clientId,
    };
  }

  // 3. Default: no auth
  return {
    method: "none",
    url: "",
    realm: "",
    clientId: "",
  };
}
