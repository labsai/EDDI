/**
 * Human-readable labels for the role identifiers that templates and groups
 * carry. A role is an identifier the engine matches on (`DEVIL_ADVOCATE`,
 * `humanDirector`, `PRO`), and showing it raw in a chip reads as an enum leak.
 */

/**
 * `DEVIL_ADVOCATE` → "Devil advocate", `humanDirector` → "Human director".
 *
 * Already-readable roles ("Marketing", "Party A", "PRO", "QA") pass through
 * unchanged: only an underscore or a lower-to-upper camel boundary is treated
 * as a word break, so a short all-caps token is never turned into "Qa".
 */
export function humanizeRole(role: string): string {
  const trimmed = role.trim();
  if (!trimmed) return trimmed;
  const spaced = trimmed.replace(/_+/g, " ").replace(/([a-z0-9])([A-Z])/g, "$1 $2");
  if (spaced === trimmed) return trimmed;
  const lower = spaced.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/**
 * Collapse repeated roles into one entry with a count, in first-seen order —
 * four "Forecasting" analysts become `{ label: "Forecasting", count: 4 }`.
 * Null and blank roles are dropped.
 */
export function summarizeRoles(
  roles: ReadonlyArray<string | null | undefined>,
): Array<{ label: string; count: number }> {
  const counts = new Map<string, number>();
  for (const role of roles) {
    if (!role || !role.trim()) continue;
    const label = humanizeRole(role);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return [...counts.entries()].map(([label, count]) => ({ label, count }));
}
