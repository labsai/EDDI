/**
 * Pure helpers shared by the config editors. Kept out of the component files so
 * those export components only (react-refresh/only-export-components).
 */

/** Replaces `oldKey` with `newKey` in place, keeping the order of the other entries. */
export function renameKey<V>(
  entries: Record<string, V>,
  oldKey: string,
  newKey: string,
): Record<string, V> {
  return Object.fromEntries(
    Object.entries(entries).map(([k, v]) => (k === oldKey ? [newKey, v] : [k, v])),
  );
}

/** `${prefix}1`, `${prefix}2`, … — the first one that is not already taken. */
export function nextFreeKey(taken: Iterable<string>, prefix: string): string {
  const used = new Set(taken); // a Set, not `in`: "constructor" is not taken
  let n = 1;
  while (used.has(`${prefix}${n}`)) n++;
  return `${prefix}${n}`;
}

/**
 * A comma-separated HTTP status code list, `undefined` when it holds none.
 * The caller decides what "none" means: for `runOnHttpCode` it must stay
 * absent (the engine substitutes its defaults only for a missing list, and
 * `[]` matches no status code at all); for `skipOnHttpCode` `[]` is "skip
 * nothing" and is written as such.
 */
export function parseHttpCodeList(raw: string): number[] | undefined {
  const codes = raw
    .split(",")
    .map((s) => parseInt(s.trim(), 10))
    .filter((n) => !isNaN(n));
  return codes.length > 0 ? codes : undefined;
}

/**
 * How an MCP tool argument is edited. A string is a Qute template the engine
 * renders; anything else (number, boolean, object, array, null) is sent to
 * the tool as that JSON value, untouched (`McpCallsTask`).
 */
export type ToolArgumentKind = "text" | "json";

export function toolArgumentKind(value: unknown): ToolArgumentKind {
  return typeof value === "string" ? "text" : "json";
}

/** The text a JSON-kind argument is edited as. */
export function formatJsonArgument(value: unknown): string {
  return value === undefined ? "" : JSON.stringify(value);
}

/**
 * Parses what was typed into a JSON-kind argument. `{ ok: false }` for text
 * that is not JSON, so the caller can keep the last valid value rather than
 * silently storing a string.
 */
export function parseJsonArgument(raw: string): { ok: true; value: unknown } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(raw) };
  } catch {
    return { ok: false };
  }
}

/**
 * Whether `key` is one of the object's own keys. Not `key in obj`, which is
 * also true for "constructor", "toString" and the rest of the prototype (and
 * `Object.hasOwn` is past this project's `lib` target).
 */
export function hasOwnKey(obj: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(obj, key);
}
