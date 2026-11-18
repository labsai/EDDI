/**
 * Masking of credential-shaped values for screens that print stored documents
 * verbatim (the version diff).
 *
 * A config can hold a literal key typed before the vault existed, or one a
 * connector wrote in plaintext; printing it in a diff puts it on screen (and in
 * a screenshot) for anyone who can open the page. The rules follow the backend's
 * own redaction (`ConfigSecretRedactor`): the same credential names and suffixes,
 * header names ending in `key` and `Cookie`, credential-named query parameters and
 * `user:password@` in URLs, and the same test for what is NOT a literal.
 *
 * References are not secrets and stay readable — `${vault:key}`, `${vars:name}`,
 * `${connection:name}`, `${caller:token}`, and `{properties.key}` placeholders,
 * also beside a scheme word (`Authorization: Bearer ${vault:key}`) — so a changed
 * reference shows in the diff.
 *
 * A masked value carries a short fingerprint so a change between the two sides of
 * one diff still shows. It is salted with a random value drawn once per page load:
 * it cannot be matched across documents or sessions, and tells nothing about a
 * weak secret's guess.
 */

/** `${…}` references and `{name…}` templates, as the backend recognises them. */
const REFERENCE_OR_TEMPLATE = /\$\{[^{}]*\}|\{[A-Za-z_][^{}]*\}/g;

/** Words that may sit beside a reference in a credential field. */
const SCHEME_WORDS = new Set([
  "bearer", "basic", "digest", "token", "bot", "apikey", "api-key", "key", "ssws", "oauth", "negotiate", "hmac",
]);

/** Whole (normalised) names that hold a credential wherever they appear. */
const CREDENTIAL_NAMES = new Set([
  "apikey", "apikeys", "token", "secret", "secrets", "password", "passwd", "pwd", "passphrase", "credential",
  "credentials", "authorization", "connectionstring", "nonazureapikey", "secretaccesskey", "privatekey",
  "clientsecret", "signingsecret", "privatekeypem",
]);

/** Name endings that mark a credential. `token` is singular only: `maxTokens` is a count. */
const CREDENTIAL_SUFFIXES = [
  "apikey", "token", "secret", "password", "passwd", "passphrase", "privatekey", "secretkey", "credential", "credentials",
];

/** A first word that turns a credential noun into a quantity of them (`maxToken`). */
const QUANTITY_WORDS = new Set(["max", "min", "num", "number", "total", "budget", "count"]);

/** Query-parameter names that carry a credential beyond the header rule. */
const CREDENTIAL_QUERY_NAMES = new Set([
  "key", "sig", "signature", "subscriptionkey", "accesskey", "appkey", "licensekey", "clientkey", "signingkey",
]);

/** Headers whose name alone says credential (the part of the backend's header rule that does not end in "key"). */
const SENSITIVE_HEADERS = new Set([
  "authorization", "proxyauthorization", "cookie", "setcookie", "xauthtoken", "xapitoken", "xaccesstoken",
]);

const normalize = (name: string) => name.replace(/[^\p{L}\p{N}]/gu, "").toLowerCase();

/** One random value per page load; see the file header. */
const SALT = (() => {
  const bytes = new Uint32Array(2);
  try {
    crypto.getRandomValues(bytes);
  } catch {
    bytes[0] = Math.floor(Math.random() * 2 ** 32);
    bytes[1] = Math.floor(Math.random() * 2 ** 32);
  }
  return `${bytes[0]}:${bytes[1]}:`;
})();

/** A 4-hex-digit salted FNV-1a fingerprint: tells two values apart within this page, nothing more. */
function fingerprint(value: string): string {
  const input = SALT + value;
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0").slice(0, 4);
}

/** The mask shown in place of a credential value. */
export function maskCredentialValue(value: string): string {
  return `•••••• [${fingerprint(value)}]`;
}

/** Whether `name` (a field or map key) names a credential, as the backend decides it. */
export function isCredentialKey(name: string): boolean {
  const normalized = normalize(name);
  if (!normalized) return false;
  if (CREDENTIAL_NAMES.has(normalized)) return true;
  const firstWord = name
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .split(/[^A-Za-z0-9]+/)[0]
    ?.toLowerCase();
  if (firstWord && QUANTITY_WORDS.has(firstWord)) return false;
  return CREDENTIAL_SUFFIXES.some((suffix) => normalized.endsWith(suffix));
}

/** Whether a header's name says it carries a credential (also any name ending in `key`, and `Cookie`). */
function isCredentialHeader(name: string): boolean {
  const normalized = normalize(name);
  return SENSITIVE_HEADERS.has(normalized) || normalized.endsWith("key");
}

/** Whether a query parameter's name says it carries a credential. */
function isCredentialQuery(name: string): boolean {
  const normalized = normalize(name);
  return SENSITIVE_HEADERS.has(normalized) || CREDENTIAL_QUERY_NAMES.has(normalized) || normalized.endsWith("apikey");
}

/**
 * Whether `value` is a literal rather than references, templates and scheme words.
 * A value with no reference at all is the credential itself, whatever it spells.
 */
export function isLiteralCredential(value: string): boolean {
  if (value.trim() === "") return false;
  const refs = value.match(REFERENCE_OR_TEMPLATE);
  if (!refs) return true;
  const remainder = value.replace(REFERENCE_OR_TEMPLATE, " ");
  for (const word of remainder.split(/[^A-Za-z0-9-]+/)) {
    if (word && !SCHEME_WORDS.has(word.toLowerCase())) return true;
  }
  // Anything but ASCII letters/digits, whitespace and the separators a scheme uses is credential material.
  return /[^\sA-Za-z0-9\-:,;=]/.test(remainder);
}

/** Masks `user:password@` and credential-named query parameters of a URL-looking string. */
function maskUrl(value: string): string {
  let out = value.replace(/^(\s*[A-Za-z][A-Za-z0-9+.:-]{0,40}:\/\/)([^/?#@\s]*)@/, (match, scheme: string, userinfo: string) => {
    const colon = userinfo.indexOf(":");
    if (colon < 0) return match;
    const password = userinfo.slice(colon + 1);
    return isLiteralCredential(password) ? `${scheme}${userinfo.slice(0, colon)}:${maskCredentialValue(password)}@` : match;
  });
  const q = out.indexOf("?");
  if (q >= 0) {
    const hash = out.indexOf("#", q);
    const query = out.slice(q + 1, hash >= 0 ? hash : undefined);
    const masked = query
      .split("&")
      .map((pair) => {
        const eq = pair.indexOf("=");
        if (eq < 0) return pair;
        const name = pair.slice(0, eq);
        const v = pair.slice(eq + 1);
        return isCredentialQuery(name) && isLiteralCredential(v) ? `${name}=${maskCredentialValue(v)}` : pair;
      })
      .join("&");
    out = `${out.slice(0, q + 1)}${masked}${hash >= 0 ? out.slice(hash) : ""}`;
  }
  return out;
}

const looksLikeUrl = (key: string, value: string) =>
  /^\s*[A-Za-z][A-Za-z0-9+.:-]{0,40}:\/\//.test(value) || ["path", "url", "uri"].includes(normalize(key)) || normalize(key).endsWith("url");

/**
 * A copy of a parsed JSON value with credential values masked: strings under
 * credential-named keys, values of credential headers and query parameters, and
 * the credentials inside URLs. References and empty strings stay as they are.
 */
export function maskCredentials(value: unknown, container = ""): unknown {
  if (Array.isArray(value)) return value.map((v) => maskCredentials(v, container));
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, v] of Object.entries(value as Record<string, unknown>)) {
      out[key] = typeof v === "string" ? maskString(key, v, container) : maskCredentials(v, key);
    }
    return out;
  }
  return value;
}

function maskString(key: string, value: string, container: string): string {
  if (value.trim() === "") return value;
  const holder = normalize(container);
  const credentialField = holder.endsWith("headers")
    ? isCredentialHeader(key)
    : holder.endsWith("queryparams")
      ? isCredentialQuery(key)
      : isCredentialKey(key);
  if (credentialField) return isLiteralCredential(value) ? maskCredentialValue(value) : value;
  return looksLikeUrl(key, value) ? maskUrl(value) : value;
}
