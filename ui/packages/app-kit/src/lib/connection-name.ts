/**
 * The backend's connection-name grammar, defined once.
 *
 * A leading letter or digit, then up to 63 of letters, digits, dots, dashes and
 * underscores — `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, the pattern
 * `ConnectionConfiguration.validate()` saves a name under. A name is what
 * `${connection:name}` carries, and a brace, a slash or a space in it produces a
 * reference that silently never resolves.
 *
 * Two modules need it and must not drift apart: `connection-validation.ts`
 * judges the name a connection is saved under, and `secret-reference.ts` judges
 * the name a reference points at. With a copy in each, a reference could look
 * valid for a name no connection can ever have.
 *
 * **When the backend grammar changes, change it here** — both readers follow.
 */

/** The unanchored body, for embedding in a larger pattern such as a reference. */
export const CONNECTION_NAME_SOURCE = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

/** The whole-value pattern a connection's name must match. */
export const CONNECTION_NAME_PATTERN = new RegExp(`^${CONNECTION_NAME_SOURCE}$`);

/** The longest name the grammar admits. */
export const CONNECTION_NAME_MAX_LENGTH = 64;

/** Whether `name` is exactly a name the backend accepts — untrimmed, as the backend judges it. */
export function isValidConnectionName(name: string | null | undefined): boolean {
  return typeof name === "string" && CONNECTION_NAME_PATTERN.test(name);
}
