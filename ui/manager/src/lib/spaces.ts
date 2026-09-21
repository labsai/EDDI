/**
 * The space and subject identifiers workspaces are built on.
 *
 * **This mirrors `Subjects.java` on the backend and must stay in step with it.**
 * A space id built differently here does not fail loudly — it silently selects a
 * workspace that matches nothing, which reads as "you have no agents". The two
 * encodings that matter:
 *
 * - the delimiter and percent characters are escaped, because a space id ends up
 *   as one token in a pipe-delimited access index;
 * - Keycloak's leading slash is normalised away, so a group claim of
 *   `/engineering` and a configured name of `engineering` mean the same space.
 */

export const USER_PREFIX = "user:";
export const TEAM_PREFIX = "team:";

/** Space id for data that predates ownership being recorded. */
export const LEGACY_SPACE = "legacy";

/** Escapes the two characters that would otherwise forge extra index tokens. */
export function encodeSubjectPart(raw: string): string {
  return raw.replace(/%/g, "%25").replace(/\|/g, "%7C");
}

/** Inverse of {@link encodeSubjectPart}, for display. */
export function decodeSubjectPart(encoded: string): string {
  // Pipe before percent, so a value that literally contained `%7C` (encoded as
  // `%257C`) comes back as `%7C` rather than being decoded twice into a pipe.
  return encoded.replace(/%7C/gi, "|").replace(/%25/g, "%");
}

/**
 * Strips the slashes Keycloak wraps group paths in. Nested paths keep their
 * internal separators and stay distinct: `/engineering/backend` is its own
 * space and does **not** confer `/engineering`.
 */
export function normalizeGroupPath(groupPath: string): string {
  return groupPath.trim().replace(/^\/+/, "").replace(/\/+$/, "");
}

/** The subject and personal-space id for a principal. */
export function userSubject(principal: string): string | null {
  const trimmed = principal?.trim();
  if (!trimmed) return null;
  return USER_PREFIX + encodeSubjectPart(trimmed);
}

/** The subject and space id for a Keycloak group. */
export function teamSubject(groupPath: string): string | null {
  const normalized = normalizeGroupPath(groupPath ?? "");
  if (!normalized) return null;
  return TEAM_PREFIX + encodeSubjectPart(normalized);
}

/**
 * A space id rendered for a human.
 *
 * Falls back to the raw id rather than hiding it: an unrecognised space is
 * something the reader should be able to see and report, not something to
 * paper over with a friendly-looking guess.
 */
export function describeSpace(spaceId: string | null | undefined): string | null {
  if (!spaceId) return null;
  if (spaceId === LEGACY_SPACE) return LEGACY_SPACE;
  if (spaceId.startsWith(USER_PREFIX)) {
    return decodeSubjectPart(spaceId.slice(USER_PREFIX.length));
  }
  if (spaceId.startsWith(TEAM_PREFIX)) {
    return decodeSubjectPart(spaceId.slice(TEAM_PREFIX.length));
  }
  return spaceId;
}

/** Whether a subject names an individual rather than a team. */
export function isUserSubject(subject: string): boolean {
  return subject.startsWith(USER_PREFIX);
}

/**
 * Normalises what a person typed into a share box.
 *
 * A bare name is read as a user, because that is what people type. Anything
 * carrying an unknown prefix is rejected rather than guessed at: a typo that
 * silently became a subject nobody holds looks exactly like a successful share.
 */
export function parseSubjectInput(input: string): { subject: string } | { error: "empty" | "unknown-prefix" } {
  const trimmed = input?.trim() ?? "";
  if (!trimmed) return { error: "empty" };

  if (trimmed.startsWith(USER_PREFIX)) {
    const subject = userSubject(trimmed.slice(USER_PREFIX.length));
    return subject ? { subject } : { error: "empty" };
  }
  if (trimmed.startsWith(TEAM_PREFIX)) {
    const subject = teamSubject(trimmed.slice(TEAM_PREFIX.length));
    return subject ? { subject } : { error: "empty" };
  }
  if (trimmed.includes(":")) return { error: "unknown-prefix" };

  const subject = userSubject(trimmed);
  return subject ? { subject } : { error: "empty" };
}
