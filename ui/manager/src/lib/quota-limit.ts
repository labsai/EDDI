/**
 * Text → limit. Empty is "unlimited" (-1), never 0: `Number("")` is 0, and a
 * limit of 0 blocks the tenant outright, so clearing a field used to lock
 * everyone out on save. Text that is not a number yet (a lone "-") is `null`.
 */
export function parseQuotaLimit(text: string, { integer = false }: { integer?: boolean } = {}): number | null {
  const trimmed = text.trim();
  if (trimmed === "") return -1;
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return null;
  // -1 is the only meaningful negative ("unlimited"); anything else below zero
  // is a typo the backend would store as-is. Counts must be whole numbers.
  if (n < 0 && n !== -1) return null;
  if (integer && !Number.isInteger(n)) return null;
  return n;
}
