/**
 * Text → the agent's per-conversation cost ceiling (`maxCostPerConversationUsd`).
 *
 * Unlike a tenant quota limit, "unlimited" is the field being ABSENT, not -1: the
 * backend reads a negative value as unlimited too, but writing one would leave a
 * stored document that says something the author never typed. So:
 *   - empty            → `{ ok: true, value: undefined }` (drop the field)
 *   - 0 or more        → `{ ok: true, value }` (0 allows no priced spend at all)
 *   - anything else    → `{ ok: false }` (a lone "-", "1e", a negative amount)
 */
export type CostCeilingParse = { ok: true; value: number | undefined } | { ok: false };

export function parseCostCeiling(text: string): CostCeilingParse {
  const trimmed = text.trim();
  if (trimmed === "") return { ok: true, value: undefined };
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n < 0) return { ok: false };
  return { ok: true, value: n };
}
