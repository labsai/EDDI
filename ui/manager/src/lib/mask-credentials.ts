/**
 * Masking of credential-shaped values for screens that print stored documents
 * verbatim (the version diff).
 *
 * A config can hold a literal key typed before the vault existed, or one a
 * connector wrote in plaintext; printing it in a diff puts it on screen (and in
 * a screenshot) for anyone who can open the page. The rules follow the backend's
 * own redaction (`ConfigSecretRedactor`): the same credential names and suffixes,
 * the shared header/query-name rule (`UriRedactor.isSensitiveHeaderName`) plus
 * header names ending in `key` and `Cookie`, credential-named query parameters,
 * `user:password@` in URLs (through the last `@`), credential arrays, header maps
 * stored as JSON strings, credential fields of a JSON `body`, and the same test for
 * what is NOT a literal.
 *
 * Not ported: the backend's free-text shape scan (`SecretRedactionFilter`), which
 * also catches a key-shaped value under an innocent name. This page is a display
 * aid for the people already allowed to read the document; a non-admin's read is
 * redacted by the server before it gets here.
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

/*
 * The shared header-and-query rule (`UriRedactor.isSensitiveHeaderName`): a name
 * is sensitive when its words run together contain a credential word, when one
 * of its words is a short credential word, or when a word ends in the auth family.
 */
/** Matched anywhere in the run-together words (`access_token`, `X-Auth-Token`, `client_secret`). */
const CREDENTIAL_WORDS = ["token", "secret", "credential", "password", "passwd", "apikey", "cookie", "signature"];
/** Too short or common to match inside another word: whole words only. */
const CREDENTIAL_WHOLE_WORDS = new Set(["sig", "session", "sessionid", "sid"]);
/** The auth family, as the END of a word (`oauth`, `Proxy-Authorization`; not `author`). */
const AUTH_WORD_ENDINGS = ["auth", "authorization", "authorisation", "authentication", "authenticate", "authn"];

const normalize = (name: string) => name.replace(/[^\p{L}\p{N}]/gu, "").toLowerCase();

/** The words of a name, lower-cased: split on non-alphanumerics and at a lower-to-upper case change. */
function splitWords(name: string): string[] {
  const words: string[] = [];
  let word = "";
  for (let i = 0; i < name.length; i++) {
    const c = name[i]!;
    const alnum = /[\p{L}\p{N}]/u.test(c);
    const upper = c !== c.toLowerCase() && c === c.toUpperCase();
    const prev = name[i - 1];
    const prevLower = prev !== undefined && prev !== prev.toUpperCase() && prev === prev.toLowerCase();
    if ((!alnum || (upper && prevLower)) && word) {
      words.push(word);
      word = "";
    }
    if (alnum) word += c.toLowerCase();
  }
  if (word) words.push(word);
  return words;
}

/** The backend's shared rule for a header or query-parameter name. */
function isSensitiveHeaderName(name: string): boolean {
  const words = splitWords(name);
  const runTogether = words.join("");
  if (CREDENTIAL_WORDS.some((w) => runTogether.includes(w))) return true;
  return words.some((w) => CREDENTIAL_WHOLE_WORDS.has(w) || AUTH_WORD_ENDINGS.some((e) => w.endsWith(e)));
}

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
  const firstWord = splitWords(name)[0];
  if (firstWord && QUANTITY_WORDS.has(firstWord)) return false;
  return CREDENTIAL_SUFFIXES.some((suffix) => normalized.endsWith(suffix));
}

/** Whether a header's name says it carries a credential (the shared rule, any name ending in `key`, and `Cookie`). */
function isCredentialHeader(name: string): boolean {
  const normalized = normalize(name);
  return isSensitiveHeaderName(name) || normalized.endsWith("key") || normalized === "cookie";
}

/** Whether a query parameter's name says it carries a credential, also when it is percent-encoded (`%61pi_key`). */
function isCredentialQuery(name: string): boolean {
  const judge = (n: string) => {
    const normalized = normalize(n);
    return isSensitiveHeaderName(n) || CREDENTIAL_QUERY_NAMES.has(normalized) || normalized.endsWith("apikey");
  };
  if (judge(name)) return true;
  try {
    return judge(decodeURIComponent(name.replace(/\+/g, " ")));
  } catch {
    return false;
  }
}

/** Whether a value is nothing but references and templates (and whitespace). */
function isOnlyReferences(value: string): boolean {
  return value.replace(REFERENCE_OR_TEMPLATE, "").trim() === "";
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

/**
 * Masks the userinfo password and the credential-named query values of a URL or
 * a path with a query, split the way the backend's `redactUrl` splits it: the
 * fragment, then the query, then the authority up to the first `/` after `://`,
 * whose password runs from the first `:` to the LAST `@` — a password may itself
 * contain `@`. A password or query value made only of references stays readable.
 */
function maskUrl(value: string): string {
  const hash = value.indexOf("#");
  const fragment = hash >= 0 ? value.slice(hash) : "";
  let text = hash >= 0 ? value.slice(0, hash) : value;
  const question = text.indexOf("?");
  const query = question >= 0 ? text.slice(question + 1) : null;
  if (question >= 0) text = text.slice(0, question);

  const scheme = text.indexOf("://");
  if (scheme >= 0) {
    const authorityStart = scheme + 3;
    const pathStart = text.indexOf("/", authorityStart);
    let authority = pathStart < 0 ? text.slice(authorityStart) : text.slice(authorityStart, pathStart);
    const at = authority.lastIndexOf("@");
    if (at >= 0) {
      const userInfo = authority.slice(0, at);
      const colon = userInfo.indexOf(":");
      const password = colon >= 0 ? userInfo.slice(colon + 1) : "";
      if (password !== "" && !isOnlyReferences(password)) {
        authority = `${userInfo.slice(0, colon + 1)}${maskCredentialValue(password)}${authority.slice(at)}`;
      }
    }
    text = text.slice(0, authorityStart) + authority + (pathStart < 0 ? "" : text.slice(pathStart));
  }

  if (query !== null) {
    const masked = query
      .split("&")
      .map((pair) => {
        const eq = pair.indexOf("=");
        if (eq < 0) return pair;
        const name = pair.slice(0, eq);
        const v = pair.slice(eq + 1);
        if (v === "" || isOnlyReferences(v)) return pair;
        return isCredentialQuery(name) ? `${name}=${maskCredentialValue(v)}` : pair;
      })
      .join("&");
    text = `${text}?${masked}`;
  }
  return text + fragment;
}

/**
 * A header or query-parameter map stored as a JSON string (an LLM task's
 * `customHeaders`): each entry judged as in a real map. Null when nothing changed.
 */
function maskEmbeddedMap(container: string, value: string): string | null {
  if (!value.trimStart().startsWith("{")) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  let changed = false;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
    if (v !== null && (typeof v === "string" || typeof v === "number" || typeof v === "boolean")) {
      const text = String(v);
      const masked = maskString(k, text, container);
      if (masked !== text) {
        out[k] = masked;
        changed = true;
        continue;
      }
    }
    out[k] = v;
  }
  return changed ? JSON.stringify(out) : null;
}

/**
 * A request body that is one JSON document, with every literal scalar under a
 * credential-named field masked at any depth (`{"password": "x"}` however short).
 * Null when the body is not JSON or holds no such field.
 */
function maskJsonBody(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return null;
  }
  let changed = false;
  const walk = (node: unknown, name: string): unknown => {
    if (Array.isArray(node)) return node.map((el) => walk(el, name));
    if (node !== null && typeof node === "object") {
      const out: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(node as Record<string, unknown>)) out[k] = walk(v, k);
      return out;
    }
    if (node !== null && (typeof node === "string" || typeof node === "number" || typeof node === "boolean")) {
      const text = String(node);
      if (text.trim() !== "" && isCredentialKey(name) && isLiteralCredential(text)) {
        changed = true;
        return maskCredentialValue(text);
      }
    }
    return node;
  };
  const masked = walk(parsed, "");
  return changed ? JSON.stringify(masked) : null;
}

const looksLikeUrl = (key: string, value: string) =>
  /^\s*[A-Za-z][A-Za-z0-9+.:-]{0,40}:\/\//.test(value) || ["path", "url", "uri"].includes(normalize(key)) || normalize(key).endsWith("url");

/**
 * A copy of a parsed JSON value with credential values masked: strings under
 * credential-named keys, values of credential headers and query parameters, and
 * the credentials inside URLs. References and empty strings stay as they are.
 */
export function maskCredentials(value: unknown, container = ""): unknown {
  if (Array.isArray(value)) {
    // An element has no name of its own: it is judged by the array's —
    // `"apiKeys": ["sk-…"]` is a list of credentials.
    return value.map((v) => (typeof v === "string" ? maskString(container, v, "") : maskCredentials(v, container)));
  }
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
  if (holder.endsWith("headers") || holder.endsWith("queryparams")) return value;
  const name = normalize(key);
  if (name.endsWith("headers") || name.endsWith("queryparams")) return maskEmbeddedMap(key, value) ?? value;
  if (looksLikeUrl(key, value)) return maskUrl(value);
  if (name === "body") return maskJsonBody(value) ?? value;
  return value;
}
