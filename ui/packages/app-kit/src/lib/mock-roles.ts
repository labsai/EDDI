/**
 * The realm roles the mock backend should pretend the user holds — a test seam
 * for the UI tier of the E2E suite, which runs against MSW with no Keycloak and
 * therefore, by default, as an auth-disabled user who may do everything.
 *
 * Read from `localStorage["eddi-mock-roles"]` (a comma-separated list) only in a
 * development build with the mock API active (`__EDDI_MOCK_ACTIVE__`, set by
 * `main.tsx` after MSW starts). A production bundle compiles the check away
 * (`import.meta.env.DEV` is a build-time constant), and a dev session against
 * a real backend ignores it — the server's own role checks are the authority
 * there, and pretending otherwise would only hide its answers.
 *
 * @returns the roles, or null when no override applies
 */
export function mockRolesOverride(): string[] | null {
  if (!import.meta.env.DEV) return null;
  if (typeof window === "undefined") return null;
  if (!(window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__) return null;
  try {
    const raw = window.localStorage.getItem("eddi-mock-roles");
    if (!raw) return null;
    const roles = raw
      .split(",")
      .map((r) => r.trim())
      .filter(Boolean);
    return roles.length > 0 ? roles : null;
  } catch {
    return null;
  }
}
