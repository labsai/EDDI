/**
 * The one definition of what a secret *reference* looks like.
 *
 * A reference is a pointer EDDI resolves at use time — `${vault:jira-token}`,
 * `${vars:tenant-key}` — as opposed to the secret itself. Three places need to
 * agree about the grammar, and before this module existed they did not:
 *
 *  - `connection-validation.ts` decides whether a document will be accepted;
 *  - `secret-key-picker.tsx` decides whether to render a chip or a text box,
 *    and canonicalises what the user typed;
 *  - `header-value-field.tsx` decides whether a template can be shown as two
 *    guided fields.
 *
 * Each spelled the scheme list out for itself, and the picker's copy was missing
 * `vars` — so a `${vars:…}` reference the backend accepts was refused by the one
 * component whose job is to help you write one. A fourth scheme would have had
 * to be added in three files with nothing tying them together.
 *
 * ## Two levels of strictness, deliberately
 *
 * `isSecretReference` is what the **backend** accepts: anchored, braced, exactly
 * one reference and nothing else. `hasReferencePrefix` is what a **person
 * typing** looks like: it also admits the unbraced legacy spellings and
 * half-finished input, so the UI can recognise intent before the value is
 * valid. Conflating them is how a field either rejects legal documents or
 * promises that an invalid one will save.
 *
 * ## What is deliberately NOT here
 *
 * `src/lib/operator/vault-ref.ts` keeps its own parser. It answers a different
 * question — "what key *name* should the operator config store?" — deliberately
 * tolerates sloppy input, and returns null for anything else. It is also under
 * the mutation-testing gate. Merging the two would trade a clear, separately
 * guaranteed contract for one shared regex serving two different callers.
 */

/**
 * Schemes EDDI resolves. `eddivault` is the legacy spelling of `vault`; both
 * are accepted by the backend's own pattern, so both are accepted here.
 */
export const REFERENCE_SCHEMES = ["vault", "eddivault", "vars"] as const;
export type ReferenceScheme = (typeof REFERENCE_SCHEMES)[number];

const SCHEME_ALTERNATION = REFERENCE_SCHEMES.join("|");

/**
 * A value that is exactly one braced reference.
 *
 * Anchored, mirroring the backend's `REFERENCE_ONLY.matcher(value).matches()`:
 * a value that merely *contains* a reference is not one, or
 * `sk-live-x${vault:unused}` would pass while carrying a literal key. The
 * `{1,256}` body bound is the backend's too.
 */
const CANONICAL = new RegExp(`^\\$\\{(${SCHEME_ALTERNATION}):([^}]{1,256})\\}$`);

/**
 * The unbraced spellings a person pastes or half-remembers — `vault:key`.
 *
 * The backend refuses these, so they exist here only to be recognised and
 * corrected, never to be stored.
 *
 * The body excludes `}` for the same reason the canonical pattern does: with
 * `.` there, `vault:key}` parsed as a body of `key}` and canonicalised to
 * `${vault:key}}` — a value this module's own `isSecretReference` rejects, so
 * the correction produced something no less broken than the input.
 */
const UNBRACED = new RegExp(`^(${SCHEME_ALTERNATION}):([^}]{1,256})$`, "i");

/**
 * Anything heading *towards* a reference, including input that is not one yet.
 *
 * Prefix-based on purpose: `${vault:` is not a valid reference but is
 * unmistakably someone typing one, and a field that flips to "plaintext secret"
 * halfway through the word is worse than one that waits.
 */
const PREFIXES = REFERENCE_SCHEMES.flatMap((scheme) => [
  `${scheme}:`,
  `\${${scheme}:`,
]);

/**
 * Every interpolated `${…}` segment in a template, in order.
 *
 * A function rather than an exported global regex: a `/g` pattern carries
 * mutable `lastIndex`, so sharing one across modules means one caller's `.test()`
 * silently changes where another caller's next scan starts. Handing back an
 * array costs nothing here and removes the whole class of bug.
 */
export function interpolatedSegments(value: string): string[] {
  return [...value.matchAll(/\$\{[^}]{0,256}\}/g)].map((match) => match[0]);
}

export interface ParsedReference {
  scheme: ReferenceScheme;
  /** What follows the colon — a vault key, or a variable name. */
  body: string;
}

/** Whether `value` is exactly one braced reference — the backend's rule. */
export function isSecretReference(value: string | null | undefined): boolean {
  return typeof value === "string" && CANONICAL.test(value.trim());
}

/** The scheme and body of a braced reference, or null if it is not one. */
export function parseSecretReference(
  value: string | null | undefined,
): ParsedReference | null {
  if (typeof value !== "string") return null;
  const match = CANONICAL.exec(value.trim());
  if (!match) return null;
  return { scheme: match[1] as ReferenceScheme, body: match[2]! };
}

/**
 * Whether the value looks like somebody meant a reference — braced or not,
 * finished or not. Use for "should this render as a reference?", never for
 * "will the backend accept this?".
 */
export function hasReferencePrefix(value: string | null | undefined): boolean {
  if (typeof value !== "string") return false;
  const trimmed = value.trim().toLowerCase();
  return PREFIXES.some((prefix) => trimmed.startsWith(prefix));
}

/** Build a braced reference. */
export function toReference(scheme: ReferenceScheme, body: string): string {
  return `\${${scheme}:${body}}`;
}

/** Build a `${vault:…}` reference — the common case. */
export function toVaultReference(keyName: string): string {
  return toReference("vault", keyName);
}

/**
 * Put an unbraced reference into the braced form, preserving its scheme.
 *
 * Returns null when there is nothing to do — the value is already canonical,
 * is not reference-shaped, or is still being typed (an unclosed `${vault:`).
 * Rewriting a half-typed value is what turns `${vault:` into `${vault:}` and
 * strands the rest of the word past the closing brace, so an unclosed braced
 * value is deliberately left alone.
 *
 * The scheme is carried across rather than normalised to `vault`: `eddivault`
 * and `vars` resolve differently, and silently rewriting one into another would
 * change which secret a connection reads.
 */
export function canonicalizeReference(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed || CANONICAL.test(trimmed)) return null;
  if (trimmed.startsWith("${")) return null;

  const match = UNBRACED.exec(trimmed);
  if (!match) return null;
  return toReference(match[1]!.toLowerCase() as ReferenceScheme, match[2]!.trim());
}

/**
 * What to show for a reference.
 *
 * A vault key renders bare, as it always has. `${vars:…}` keeps its scheme,
 * because "which global variable" is the whole content of the value and
 * dropping the prefix would make it indistinguishable from a vault key that
 * does not exist.
 */
export function referenceLabel(value: string): string {
  const parsed = parseSecretReference(value);
  if (parsed) {
    return parsed.scheme === "vars" ? `vars:${parsed.body}` : parsed.body;
  }
  // Not canonical — an unbraced or half-typed value. Show whatever follows the
  // scheme so the chip is still readable while it is being corrected.
  const trimmed = value.trim();
  const unbraced = UNBRACED.exec(trimmed);
  if (unbraced) {
    return unbraced[1]!.toLowerCase() === "vars"
      ? `vars:${unbraced[2]}`
      : unbraced[2]!;
  }
  const opened = trimmed.replace(/^\$\{/, "").replace(/\}$/, "");
  const colon = opened.indexOf(":");
  return colon >= 0 ? opened.slice(colon + 1) : opened;
}

/**
 * Whether a reference points into the vault, as opposed to the variable store.
 *
 * The picker checks a vault key against the key list it can see; a `${vars:…}`
 * reference resolves somewhere it cannot, so checking it there would flag every
 * one of them as missing.
 */
export function isVaultScheme(value: string): boolean {
  const parsed = parseSecretReference(value);
  if (parsed) return parsed.scheme !== "vars";
  const unbraced = UNBRACED.exec(value.trim());
  return unbraced ? unbraced[1]!.toLowerCase() !== "vars" : true;
}

/**
 * Split a template into its literal prefix and its single trailing reference.
 *
 * `"Bearer ${vault:jira-token}"` → `{ prefix: "Bearer ", reference: "${vault:jira-token}" }`.
 * Returns null for anything else — two references, a reference in the middle,
 * or a bare literal — which are all legal templates that simply cannot be shown
 * as two fields.
 */
export function splitTemplate(
  value: string,
): { prefix: string; reference: string } | null {
  const trimmed = value.trim();
  const braceAt = trimmed.indexOf("${");
  if (braceAt < 0) return null;

  const prefix = trimmed.slice(0, braceAt);
  const reference = trimmed.slice(braceAt);
  // A `$` in the literal half would round-trip into a different string, and a
  // second reference means the tail is not one reference.
  if (prefix.includes("$")) return null;
  if (!CANONICAL.test(reference)) return null;
  return { prefix, reference };
}
