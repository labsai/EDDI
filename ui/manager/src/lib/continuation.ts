/**
 * Self-continuation settings of an agent (`maxContinuations`, `continuationInput`).
 *
 * The bounds mirror the backend's save-time validation (`AgentStore.validateContinuation`):
 * a value outside them is refused with a 400, so the editor refuses it first.
 */
export const MAX_CONTINUATIONS = 10_000;
export const MAX_CONTINUATION_INPUT_LENGTH = 4096;

/**
 * Text → `maxContinuations`. "Off" is the field being ABSENT (the backend treats
 * unset and 0 alike), so an empty box drops it:
 *   - empty                    → `{ ok: true, value: undefined }`
 *   - a whole number 0..10000  → `{ ok: true, value }`
 *   - anything else            → `{ ok: false }`
 */
export type MaxContinuationsParse = { ok: true; value: number | undefined } | { ok: false };

export function parseMaxContinuations(text: string): MaxContinuationsParse {
  const trimmed = text.trim();
  if (trimmed === "") return { ok: true, value: undefined };
  if (!/^\d+$/.test(trimmed)) return { ok: false };
  const n = Number(trimmed);
  if (!Number.isSafeInteger(n) || n > MAX_CONTINUATIONS) return { ok: false };
  return { ok: true, value: n };
}
