import { createContext } from "react";
import type { AuthConfig } from "@/lib/auth-config";

export interface AuthUser {
  username: string;
  firstName: string;
  lastName: string;
  email: string;
  fullName: string;
}

export interface AuthContextValue {
  /** Whether the user is authenticated (always true when auth is disabled) */
  authenticated: boolean;
  /** Whether auth is still initializing */
  loading: boolean;
  /** Current user profile (null when auth disabled) */
  user: AuthUser | null;
  /** Realm roles the user has */
  roles: string[];
  /** The auth method in use */
  method: AuthConfig["method"];
  /** Trigger login (no-op when auth disabled) */
  login: () => void;
  /** Trigger logout (no-op when auth disabled) */
  logout: () => void;
}

export const GUEST_CONTEXT: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: [],
  method: "none",
  login: () => {},
  logout: () => {},
};

export const AuthContext = createContext<AuthContextValue>(GUEST_CONTEXT);
