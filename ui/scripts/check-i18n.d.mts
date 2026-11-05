/**
 * Types for the pure helpers in check-i18n.mjs, so the gate's logic can be
 * unit-tested from the TypeScript suite. The script itself stays plain JS
 * because CI runs it with bare `node`, before any build step exists — the same
 * arrangement as audit-prod.mjs.
 */

/** A key the code passes to `t()` that `en.json` does not define. */
export interface MissingKey {
  key: string;
  /** "file:line" of the first reference, so the failure is actionable. */
  where: string;
}

/** A key present in `en.json` but absent from one locale. */
export interface ParityGap {
  code: string;
  key: string;
}

/** One key passed to `t()` with more than one English default. */
export interface Collision {
  key: string;
  defaults: string[];
}

export interface CheckResult {
  missing: MissingKey[];
  parity: ParityGap[];
  /** Keys a locale still carries after `en.json` dropped them. */
  orphans: ParityGap[];
  /** New collisions only — those in `KNOWN_COLLISIONS` are excluded. */
  collisions: Collision[];
  localeCount: number;
  enKeyCount: number;
}

/** Run all three drift checks. Pure — reads the filesystem, throws nothing. */
export function check(options?: { localesDir?: string; srcDir?: string }): CheckResult;

/**
 * Every key referenced by a string literal passed to `t()` or `i18nKey`.
 * Maps key → "file:line" of its first occurrence. Test files are skipped.
 */
export function collectUsedKeys(
  files: string[],
  read?: (file: string) => string,
): Map<string, string>;

/** Whether `key` is defined outright or via any of its plural variants. */
export function isSatisfied(key: string, definedKeys: Set<string>): boolean;

/**
 * Whether an extra key in a locale is a legitimate CLDR plural form rather than
 * a leftover — Arabic has six categories, es/fr/pt add `many`.
 */
export function isLegitimatePluralVariant(key: string, enKeys: Set<string>): boolean;

/**
 * Collisions that predate the gate. Frozen so the count can only fall; removing
 * an entry is the fix, adding one is not allowed.
 */
export const KNOWN_COLLISIONS: Set<string>;

/** key -> every distinct English default it is called with. */
export function collectDefaults(
  files: string[],
  read?: (file: string) => string,
): Map<string, Set<string>>;
