/**
 * Text → limit. Empty is "unlimited" (-1), never 0: `Number("")` is 0, and a
 * limit of 0 blocks the tenant outright, so clearing a field used to lock
 * everyone out on save. Text that is not a number yet (a lone "-") is `null`.
 */
export function parseQuotaLimit(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return -1;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}
