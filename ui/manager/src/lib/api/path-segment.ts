/**
 * One URL path segment, built from an id the app did not author.
 *
 * Route params, `?conversationId=` and API responses all end up interpolated into
 * request paths like `/agentstore/agents/${id}`. Left raw, a crafted deep link
 * retargets the call: React Router decodes `%2F` in a param, so `id = "a/../b"`
 * reaches the template as a real path, and `fetch` normalises the `..` away
 * before the request leaves, so a "delete this agent" button can end up
 * deleting a different store's resource.
 *
 * `seg` therefore does two things. It `encodeURIComponent`s the id, so nothing in
 * it can change the path's shape, and it refuses outright the ids that no EDDI
 * resource has (a slash, a backslash, a `..`, whitespace or a control
 * character, or nothing at all) rather than quietly sending a mangled request.
 * It throws; callers that interpolate into a request path are inside a
 * TanStack Query function or a mutation, where a thrown error surfaces as the
 * ordinary error state.
 */
export class InvalidPathSegmentError extends Error {
  constructor(reason: string) {
    super(`Refusing to use an id in a request path: ${reason}`);
    this.name = "InvalidPathSegmentError";
  }
}

// eslint-disable-next-line no-control-regex
const FORBIDDEN = /[/\\\s\u0000-\u001f\u007f]/;

/** The reason `value` is unusable as one path segment, or `null` when it is fine. */
export function pathSegmentProblem(value: unknown): string | null {
  if (typeof value !== "string" && typeof value !== "number") return "it is not a string";
  const s = String(value);
  if (s === "") return "it is empty";
  if (FORBIDDEN.test(s)) return "it contains a slash, backslash, whitespace or control character";
  if (s.includes("..")) return "it contains '..'";
  return null;
}

/** `true` when `seg(value)` would not throw. */
export function isSafePathSegment(value: unknown): value is string | number {
  return pathSegmentProblem(value) === null;
}

/**
 * Encode a free-form NAME (a connection name, a secret key, a tenant or user
 * id that an administrator typed) as one path segment.
 *
 * Unlike {@link seg} this accepts spaces and slashes, because those names can
 * legitimately contain them and `encodeURIComponent` already makes them inert
 * (`a/b` becomes `a%2Fb`). What encoding cannot neutralise is a segment that is
 * exactly `.` or `..`: `fetch` collapses those before the request leaves, so
 * they are the one thing refused, along with an empty name.
 */
export function nameSeg(value: string): string {
  if (typeof value !== "string" || value === "") {
    throw new InvalidPathSegmentError("it is empty");
  }
  if (value === "." || value === "..") {
    throw new InvalidPathSegmentError("it is a dot segment");
  }
  return encodeURIComponent(value);
}

/** Encode `value` as a single path segment, or throw {@link InvalidPathSegmentError}. */
export function seg(value: string | number): string {
  const problem = pathSegmentProblem(value);
  if (problem) throw new InvalidPathSegmentError(problem);
  return encodeURIComponent(String(value));
}
