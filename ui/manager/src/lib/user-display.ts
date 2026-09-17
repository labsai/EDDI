import type { AuthUser } from "@/components/auth/auth-context";

/**
 * How the signed-in user is labelled in the chrome (top-bar menu, sidebar).
 *
 * Every field of {@link AuthUser} can legitimately be empty: they come from the
 * OIDC `profile` and `email` claims, and a realm that does not grant those
 * scopes — including one imported with a custom `clientScopes` list, which makes
 * Keycloak skip creating its built-in scopes — sends a token carrying none of
 * them. The avatar used to fall back to a literal "?" in that case, so callers
 * get an empty string here and render an icon instead.
 */

/** First user-perceived character, upper-cased — `Array.from` keeps surrogate pairs whole. */
function firstChar(value: string): string {
  return (Array.from(value.trim())[0] ?? "").toLocaleUpperCase();
}

/**
 * One or two initials, or `""` when no claim yields a letter or digit.
 *
 * Preference: given + family name, then the display name's first and last
 * word, then the username, then the local part of the email address.
 */
export function userInitials(user: AuthUser): string {
  const fromParts = [user.firstName, user.lastName].map(firstChar).join("");
  if (fromParts) return fromParts;

  const words = user.fullName.trim().split(/\s+/).filter(Boolean);
  const first = words[0];
  if (first !== undefined) {
    const last = words.length > 1 ? (words[words.length - 1] ?? "") : "";
    return firstChar(first) + firstChar(last);
  }

  const handle = user.username.trim() || (user.email.trim().split("@")[0] ?? "");
  // A handle like "_svc" or "123" should not produce "_" as an avatar.
  const match = handle.match(/[\p{L}\p{N}]/u);
  return match ? match[0].toLocaleUpperCase() : "";
}

/** The best human-readable name, or `""` when the token carries none. */
export function userDisplayName(user: AuthUser): string {
  return (
    user.fullName.trim() ||
    [user.firstName, user.lastName].map((n) => n.trim()).filter(Boolean).join(" ") ||
    user.username.trim() ||
    user.email.trim()
  );
}
