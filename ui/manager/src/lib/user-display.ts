import type { AuthUser } from "@/components/auth/auth-context";

/**
 * How the signed-in user is labelled in the chrome (top-bar menu, sidebar).
 *
 * Every field of {@link AuthUser} can legitimately be empty: they come from the
 * OIDC `profile` and `email` claims, and a realm that does not grant those
 * scopes — as the realm EDDI shipped from 6.1.0 to 6.4.0 did not — sends a token
 * carrying none of them. The avatar used to fall back to a literal "?" in that
 * case, so callers get an empty string here and render an icon instead.
 */

/**
 * Thai and Lao vowels written before the consonant they follow in speech
 * (เ แ โ ใ ไ, ເ ແ ໂ ໃ ໄ). As a lone initial they are meaningless, so the
 * consonant after them is used instead.
 */
const PREPOSED_VOWEL = "เ-ไເ-ໄ";
const INITIAL = new RegExp(`(?![${PREPOSED_VOWEL}])[\\p{L}\\p{N}]`, "u");
const ARABIC = /\p{Script=Arabic}/u;

/**
 * The first letter or digit of `value`, upper-cased, or `""`.
 *
 * A bare letter rather than a whole grapheme: a base letter always renders on
 * its own, while its marks (a Devanagari virama, a Thai tone mark) do not read
 * as an initial. NFC first, so a decomposed "é" keeps its accent. Emoji,
 * punctuation and symbols are skipped. `toUpperCase`, not `toLocaleUpperCase`:
 * the browser's locale is not the app's language, and a Turkish system locale
 * would otherwise turn "isabel" into "İ".
 */
function initialOf(value: string): string {
  return value.normalize("NFC").match(INITIAL)?.[0].toUpperCase() ?? "";
}

/**
 * Two initials side by side. Arabic letters join to their neighbours, so "م"
 * and "ع" would render as the word "مع"; a zero-width non-joiner keeps them apart.
 */
function pair(first: string, second: string): string {
  if (!second) return first;
  return ARABIC.test(first) && ARABIC.test(second)
    ? `${first}‌${second}`
    : first + second;
}

/**
 * One or two initials, or `""` when no claim yields a letter or digit.
 *
 * Preference: given + family name, then the first and last word of the display
 * name, then the username, then the email's local part.
 *
 * Name parts come before the display name — unlike {@link userDisplayName} —
 * because they are structured and the display name is free text. From Keycloak
 * the two agree (its `name` is given + family). From an identity provider whose
 * display name reads "Doe, Jane (Contractor)", the parts still give "JD" where
 * the display name would give "DC". The cost is a preferred name: a user shown
 * as "Bob Smith" whose given name is Robert gets "RS".
 */
export function userInitials(user: AuthUser): string {
  const given = initialOf(user.firstName);
  const family = initialOf(user.lastName);
  if (given || family) return pair(given || family, given ? family : "");

  const words = user.fullName.split(/\s+/).map(initialOf).filter(Boolean);
  if (words.length > 0) {
    return pair(words[0] ?? "", words.length > 1 ? (words[words.length - 1] ?? "") : "");
  }

  return initialOf(user.username) || initialOf(user.email.split("@")[0] ?? "");
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

/**
 * The email to show under the name, or `""` when there is none or it is already
 * the name — the display name falls back to the email, and a username can be the
 * email in another case.
 */
export function userSecondaryEmail(user: AuthUser): string {
  const email = user.email.trim();
  return email && email.toLowerCase() !== userDisplayName(user).toLowerCase() ? email : "";
}
