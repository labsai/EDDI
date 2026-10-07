import { useCallback, useContext } from "react";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { mayUse, rolesForScreen, ROLE_ADMIN } from "@/lib/roles";

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

/**
 * Whether the signed-in user may use something that requires one of
 * `required` — a screen, a quick action, an admin-only query.
 *
 * True with auth disabled, and true when the token carries no EDDI role at all
 * (roles mapped from another claim — see `rolesAreKnown`): the Manager then
 * offers everything and the backend decides, as before.
 */
export function useMayUse(): (required: readonly string[] | null) => boolean {
  const { method, roles } = useAuth();
  return useCallback(
    (required: readonly string[] | null) => mayUse(required, roles, method === "none"),
    [method, roles],
  );
}

/** Whether the user may open the Manager screen at `path` (see `rolesForScreen`). */
export function useMayOpenScreen(): (path: string) => boolean {
  const may = useMayUse();
  return useCallback((path: string) => may(rolesForScreen(path)), [may]);
}

/**
 * Whether admin-only endpoints are worth calling for this user. False only when
 * the token positively shows EDDI roles without `eddi-admin`.
 */
export function useIsAdminOrUnknown(): boolean {
  return useMayUse()([ROLE_ADMIN]);
}
