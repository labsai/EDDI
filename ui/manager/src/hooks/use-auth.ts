import { useContext } from "react";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";

/**
 * Access the auth context — user, roles, login/logout.
 * When auth is disabled, `authenticated` is always true and `user` is null.
 */
export function useAuth(): AuthContextValue {
  return useContext(AuthContext);
}

/**
 * Check if the current user has a specific realm role.
 * Returns true when auth is disabled (no role restrictions).
 */
export function useHasRole(role: string): boolean {
  const { method, roles } = useAuth();
  if (method === "none") return true;
  return roles.includes(role);
}
