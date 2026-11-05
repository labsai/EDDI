/**
 * The value arithmetic behind `ChipInput`, kept out of the component so a form
 * can flush uncommitted text without importing React.
 *
 * `react-refresh/only-export-components` is the immediate reason the split
 * exists, but it is the right shape anyway: "what does this list become if I
 * fold the half-typed text in" is a question about strings, and the forms that
 * ask it at save time have no business rendering anything.
 */

/**
 * One entry of pending text split into the values it represents.
 *
 * Deduplicated against ITSELF, not only against the existing list: pasting
 * `openid openid profile` otherwise yields two identical entries, which React
 * renders with duplicate keys and whose remove button deletes both at once.
 */
export function splitChipEntries(pending: string, splitOn?: RegExp): string[] {
  const trimmed = pending.trim();
  if (!trimmed) return [];
  const parts = splitOn ? trimmed.split(splitOn) : [trimmed];
  return [...new Set(parts.map((part) => part.trim()).filter(Boolean))];
}

/**
 * Fold uncommitted text into a values list.
 *
 * Returns the original array when there is nothing to add, so a caller can
 * compare by identity to decide whether anything changed.
 */
export function commitPending(
  values: string[],
  pending: string,
  splitOn?: RegExp,
): string[] {
  const fresh = splitChipEntries(pending, splitOn).filter(
    (part) => !values.includes(part),
  );
  return fresh.length === 0 ? values : [...values, ...fresh];
}
