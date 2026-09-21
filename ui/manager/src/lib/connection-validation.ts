/**
 * The connection write rules, mirrored from the backend so a failed save
 * becomes an inline hint instead of a 400.
 *
 * Every rule here is enforced server-side too — `ConnectionConfiguration.validate()`
 * in EDDI is the authority, and it also covers import, which this never sees.
 * Mirroring buys the author the message *while they are still looking at the
 * field*, which is the whole point: the backend names the field in its message,
 * but only after a round trip that throws the form's focus away.
 *
 * Deliberately pure — no React, no `t()`. Codes come out; the UI translates
 * them (`<ValidationMessage code={…} />`). The API layer has the same problem
 * and `secrets.ts` solved it the same way.
 *
 * **When the backend rule changes, change it here in the same commit.** A
 * mirror that has drifted is worse than no mirror: it either blocks a document
 * the backend would accept, or promises one it will refuse.
 */

import { CONNECTION_NAME_MAX_LENGTH, isValidConnectionName } from "./connection-name";
import {
  interpolatedSegments,
  isSecretReference as isReference,
} from "./secret-reference";

/** Fields a validation result can be keyed by. */
export type ConnectionField =
  | "name"
  | "authType"
  | "binding"
  | "baseUrlAllowlist"
  | "staticAuth.headerName"
  | "staticAuth.valueTemplate"
  | "staticAuth.username"
  | "staticAuth.passwordRef"
  | "oauth.authorizationUrl"
  | "oauth.tokenUrl"
  | "oauth.discoveryUrl"
  | "oauth.clientId"
  | "oauth.clientSecret"
  | "oauth.extraAuthParams"
  | "timeoutMs";

/**
 * A stable code for one broken rule. The UI turns it into a sentence.
 *
 * String union rather than free text so a typo is a compile error and
 * `ValidationMessage` is exhaustive over the set.
 */
export type ValidationCode =
  | "nameRequired"
  | "nameFormat"
  | "nameTooLong"
  | "bindingMismatch"
  | "callerSuppliedRefused"
  | "allowlistRequired"
  | "originNotBare"
  | "originScheme"
  | "originHost"
  | "headerNameRequired"
  | "templateRequired"
  | "templateNoReference"
  | "templateBadSegment"
  | "templateLiteralCredential"
  | "usernameRequired"
  | "secretMustBeReference"
  | "clientIdRequired"
  | "endpointRequired"
  | "endpointNotHttps"
  | "endpointUserInfo"
  | "endpointNotAbsolute"
  | "paramCredentialShaped"
  | "paramReserved"
  | "paramValueReference"
  | "paramValueTooLong"
  | "paramValueCredentialShaped"
  | "timeoutRange";

export type ConnectionErrors = Partial<Record<ConnectionField, ValidationCode>>;

/**
 * Re-exported so a caller validating a document does not need to know that the
 * grammar lives elsewhere. `secret-reference.ts` owns what a reference *is*;
 * this module owns which fields have to be one.
 */
export { isSecretReference } from "./secret-reference";

/**
 * Names that mark a value as credential-shaped, kept out of `extraAuthParams`.
 *
 * Same vocabulary as the backend's `CREDENTIAL_PARAM_NAMES`. An arbitrary
 * string map is the obvious place for an author to paste a key, and a key
 * pasted there sits in plaintext in the connection document.
 */
const CREDENTIAL_PARAM_NAMES = new Set([
  "apikey",
  "api_key",
  "apitoken",
  "api_token",
  "password",
  "passwd",
  "secret",
  "secretkey",
  "secret_key",
  "token",
  "accesstoken",
  "access_token",
  "refreshtoken",
  "refresh_token",
  "authorization",
  "auth",
  "credential",
  "credentials",
  "privatekey",
  "private_key",
  "clientsecret",
  "client_secret",
  "assertion",
  "code_verifier",
]);

/** Separators are noise: `Client-Secret`, `client.secret` and `clientSecret` are one name. */
const stripSeparators = (name: string) => name.toLowerCase().replace(/[-._]/g, "");

/**
 * The denylist with the same normalisation applied to it as to the input.
 *
 * The backend compares a key stripped of case and separators against a set
 * written in that same stripped form, so `code_verifier`, `Code-Verifier` and
 * `codeverifier` are one name there. Normalising both sides here keeps this
 * mirror equal to that rule whichever spelling the list above is written in.
 */
const NORMALIZED_CREDENTIAL_PARAM_NAMES = new Set(
  [...CREDENTIAL_PARAM_NAMES].map(stripSeparators),
);

/** Whether `name` would be refused as credential-shaped in `extraAuthParams`. */
export function isCredentialParamName(name: string): boolean {
  return NORMALIZED_CREDENTIAL_PARAM_NAMES.has(stripSeparators(name));
}

/**
 * Parameters EDDI writes onto the authorization URL itself.
 *
 * An `extraAuthParams` entry with one of these names would either be dropped
 * or override the value the flow depends on — a `redirect_uri` the provider
 * will not match, a `state` the callback cannot verify. Compared stripped of
 * case and separators, like the credential list, so `Redirect-URI` is refused
 * as surely as `redirect_uri`.
 *
 * Exactly the backend's `RESERVED_OAUTH_PARAM_NAMES`, no wider. `scope`,
 * `code` and `grant_type` are deliberately NOT here: the backend does not
 * refuse them, and a mirror that did was blocking a document the save would
 * have accepted — the one failure mode the file comment says is worse than
 * having no mirror at all.
 */
const RESERVED_OAUTH_PARAM_NAMES = new Set(
  [
    "client_id",
    "client_secret",
    "redirect_uri",
    "response_type",
    "state",
    "code_challenge",
    "code_challenge_method",
    "code_verifier",
  ].map(stripSeparators),
);

/** Whether `name` is a protocol parameter EDDI sets itself. */
export function isReservedOAuthParamName(name: string): boolean {
  return RESERVED_OAUTH_PARAM_NAMES.has(stripSeparators(name));
}

/** Longest value an extra authorization parameter may carry. */
const PARAM_VALUE_MAX_LENGTH = 512;

/**
 * Value prefixes no protocol parameter legitimately starts with and every
 * common credential format does: OpenAI/Stripe keys, Slack tokens, GitHub
 * tokens, AWS access key ids, JWTs, and a pasted `Authorization` header.
 *
 * The backend's `CREDENTIAL_SHAPED_VALUE`, character for character (the
 * inline `(?i:…)` spelled out, since JavaScript has no scoped flag). It is a
 * prefix test on the raw value — not an entropy or run-length heuristic — so
 * an opaque 36-character audience passes here exactly as it passes there,
 * and `sk-live-x` is refused here before the backend refuses it.
 */
const PARAM_VALUE_CREDENTIAL_SHAPED =
  /^(?:sk-|xox[abpsre]-|gh[pousr]_|github_pat_|AKIA|eyJ|(?:[Bb][Ee][Aa][Rr][Ee][Rr]|[Bb][Aa][Ss][Ii][Cc])\s)/;

/**
 * An extra authorization parameter's VALUE, as the backend judges it.
 *
 * The map is stored in plain text and appended to a URL the browser sees, so
 * three things are refused: a `${…}` reference (it would be resolved into the
 * authorization URL, and a secret in a query string is a secret in a proxy
 * log), a value past 512 characters, and a value that starts like a
 * credential.
 */
export function validateParamValue(value: string | null | undefined): ValidationCode | null {
  const candidate = value ?? "";
  if (candidate.includes("${")) return "paramValueReference";
  if (candidate.length > PARAM_VALUE_MAX_LENGTH) return "paramValueTooLong";
  if (PARAM_VALUE_CREDENTIAL_SHAPED.test(candidate)) return "paramValueCredentialShaped";
  return null;
}

/** The token-endpoint timeout's bounds, in milliseconds — the backend's. */
export const TIMEOUT_MS_MIN = 1;
export const TIMEOUT_MS_MAX = 60_000;

/**
 * Longest literal text allowed between references in a header template, and
 * the run that marks a shorter literal as a credential anyway.
 *
 * `Bearer ` is seven characters and passes; `sk-live-abcdef${vault:x}` is a
 * literal key with a reference stapled on, and `sk-live-abcdef` is a run of
 * fourteen from the alphabet keys are written in. The check is on the literal
 * halves only, so a long vault key NAME inside the braces is never mistaken
 * for a leaked value.
 */
const TEMPLATE_LITERAL_MAX_LENGTH = 32;
const CREDENTIAL_RUN = /[A-Za-z0-9_\-+/=.]{12,}/;

/** The text between (and around) the interpolated segments, in order. */
function literalSegments(template: string): string[] {
  const literals: string[] = [];
  let rest = template;
  for (const segment of interpolatedSegments(template)) {
    const at = rest.indexOf(segment);
    literals.push(rest.slice(0, at));
    rest = rest.slice(at + segment.length);
  }
  literals.push(rest);
  return literals;
}

/**
 * A bare origin — `scheme://host[:port]`, no path, query, fragment, userinfo or
 * trailing slash.
 *
 * Parsed rather than pattern-matched, for the reason the backend gives: a
 * string comparison accepts `api.example.com` (no scheme), which then never
 * matches anything at resolve time — an allowlist that looks right and blocks
 * everything.
 */
export function validateOrigin(origin: string): ValidationCode | null {
  const candidate = origin.trim();
  if (!candidate) return "originHost";

  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return "originNotBare";
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return "originScheme";
  if (!url.hostname) return "originHost";
  if (url.username || url.password) return "originNotBare";
  if (url.search || url.hash) return "originNotBare";
  // `new URL("https://x")` normalises the empty path to "/", so both spellings
  // are the bare origin the backend means; anything longer is a path.
  if (url.pathname !== "" && url.pathname !== "/") return "originNotBare";
  return null;
}

/**
 * A credential endpoint — absolute https, no userinfo.
 *
 * Whether the ORIGIN is one the operator trusts is a separate, deployment-level
 * allowlist this cannot see (`eddi.connections.credential-endpoint-allowlist`),
 * so a value that passes here can still come back as a 400. That refusal is
 * surfaced from the save, not predicted here.
 */
export function validateCredentialEndpoint(
  url: string | null | undefined,
  required: boolean,
): ValidationCode | null {
  const candidate = (url ?? "").trim();
  if (!candidate) return required ? "endpointRequired" : null;

  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    return "endpointNotAbsolute";
  }
  if (parsed.protocol !== "https:") return "endpointNotHttps";
  if (parsed.username || parsed.password) return "endpointUserInfo";
  if (!parsed.hostname) return "endpointNotAbsolute";
  return null;
}

/**
 * A header value template — literal text is fine, every `${…}` segment must be
 * a reference, and there has to be at least one.
 *
 * Two clauses carry the weight. A template with no interpolation at all is a
 * plaintext credential wearing a template's clothes; and a literal half that
 * is long, or that carries a run from the alphabet keys are written in, is a
 * plaintext credential with a reference stapled on. Only a short scheme
 * prefix belongs outside the braces.
 */
export function validateHeaderTemplate(
  template: string | null | undefined,
): ValidationCode | null {
  const value = (template ?? "").trim();
  if (!value) return "templateRequired";

  let sawReference = false;
  for (const segment of interpolatedSegments(value)) {
    if (!isReference(segment)) return "templateBadSegment";
    sawReference = true;
  }
  if (!sawReference) return "templateNoReference";

  for (const literal of literalSegments(value)) {
    if (literal.length > TEMPLATE_LITERAL_MAX_LENGTH || CREDENTIAL_RUN.test(literal)) {
      return "templateLiteralCredential";
    }
  }
  return null;
}

/** The three values the backend's `Binding` enum has. */
export type ConnectionBinding = "SERVICE" | "PER_USER" | "CALLER_SUPPLIED";

/**
 * The bindings a given auth type may carry.
 *
 * The backend couples the two fields in both directions: `PER_USER` requires
 * `OAUTH2_AUTHORIZATION_CODE` and vice versa (the flow files its grant under
 * whoever completed the consent screen, so a `SERVICE`-bound one would look for
 * a grant nothing can ever create), and `CALLER_SUPPLIED` requires `STATIC`
 * (the caller hands over a finished header value, so there is nothing to
 * encode, exchange or refresh). That leaves exactly one legal value for every
 * type except `STATIC`, which has two — and the only real choice.
 */
export function legalBindings(authType: string): readonly ConnectionBinding[] {
  switch (authType) {
    case "OAUTH2_AUTHORIZATION_CODE":
      return ["PER_USER"];
    case "STATIC":
      return ["SERVICE", "CALLER_SUPPLIED"];
    default:
      return ["SERVICE"];
  }
}

/**
 * The binding to store for an auth type, honouring a requested one where the
 * type permits it.
 *
 * Derived, not trusted: a draft that says `PER_USER` on a `BASIC` connection
 * — a stale field after a type switch, or an imported document — is corrected
 * to the one value the backend accepts rather than sent to fail. For `STATIC`
 * the requested value decides between `SERVICE` and `CALLER_SUPPLIED`, so a
 * connection loaded from the API keeps the binding it was stored with.
 */
export function bindingFor(
  authType: string,
  requested?: string | null,
): ConnectionBinding {
  const legal = legalBindings(authType);
  if (requested && (legal as readonly string[]).includes(requested)) {
    return requested as ConnectionBinding;
  }
  return legal[0]!;
}

/** Whether the pair is one the backend will save. */
export function isLegalBinding(authType: string, binding: string): boolean {
  return (legalBindings(authType) as readonly string[]).includes(binding);
}

/** Whether this auth type completes an OAuth flow (mirrors `AuthType.isOAuth`). */
export function isOAuthType(authType: string): boolean {
  return (
    authType === "OAUTH2_AUTHORIZATION_CODE" ||
    authType === "OAUTH2_CLIENT_CREDENTIALS"
  );
}

/** The shape `validateConnection` reads. Structural, so the API type satisfies it. */
export interface ValidatableConnection {
  name?: string;
  authType?: string;
  binding?: string | null;
  baseUrlAllowlist?: string[] | null;
  staticAuth?: {
    headerName?: string;
    valueTemplate?: string | null;
    username?: string | null;
    passwordRef?: string | null;
  } | null;
  oauth?: {
    authorizationUrl?: string | null;
    tokenUrl?: string | null;
    discoveryUrl?: string | null;
    clientId?: string | null;
    clientSecret?: string | null;
    extraAuthParams?: Record<string, string> | null;
  } | null;
  timeoutMs?: number | null;
}

/**
 * Every broken rule, keyed by the field that broke it.
 *
 * Returns all of them rather than the first, so a form can mark up three fields
 * at once — the backend can only ever name one, because it throws on the first.
 */
export function validateConnection(config: ValidatableConnection): ConnectionErrors {
  const errors: ConnectionErrors = {};

  // Judged untrimmed, as the backend judges it: a name saved with whitespace
  // the author cannot see would resolve for nobody, so the backend refuses it
  // rather than silently storing something other than what was sent. The
  // documents this validates have already been through `toStoredConnection`,
  // which trims the name on the way out — so a trailing space the wizard's
  // user typed never reaches here, and one that somehow does is refused
  // exactly where the backend would refuse it.
  const name = config.name ?? "";
  if (!name.trim()) {
    errors.name = "nameRequired";
  } else if (name.length > CONNECTION_NAME_MAX_LENGTH) {
    errors.name = "nameTooLong";
  } else if (!isValidConnectionName(name)) {
    errors.name = "nameFormat";
  }

  // Null means "the resolver's default" and is fine; a number has to be a
  // whole millisecond count inside the backend's bounds. Zero is the value
  // worth refusing loudly — it would fail every token call.
  const timeout = config.timeoutMs;
  if (
    timeout !== null &&
    timeout !== undefined &&
    (!Number.isInteger(timeout) || timeout < TIMEOUT_MS_MIN || timeout > TIMEOUT_MS_MAX)
  ) {
    errors.timeoutMs = "timeoutRange";
  }

  const allowlist = (config.baseUrlAllowlist ?? []).filter((o) => o.trim());
  if (allowlist.length === 0) {
    errors.baseUrlAllowlist = "allowlistRequired";
  } else {
    for (const origin of allowlist) {
      const problem = validateOrigin(origin);
      if (problem) {
        errors.baseUrlAllowlist = problem;
        break;
      }
    }
  }

  const authType = config.authType ?? "STATIC";
  // Judged only when present. A caller that carries no binding (the backend
  // defaults it) is not making a claim the pairing rule can refuse; the
  // documents this form sends always carry one, via `toStoredConnection`.
  const binding = config.binding ?? null;
  if (binding !== null && !isLegalBinding(authType, binding)) {
    errors.binding = "bindingMismatch";
  }

  if (authType === "STATIC" || authType === "BASIC") {
    const staticAuth = config.staticAuth ?? {};
    if (!(staticAuth.headerName ?? "").trim()) {
      errors["staticAuth.headerName"] = "headerNameRequired";
    }
    if (authType === "BASIC") {
      if (!(staticAuth.username ?? "").trim()) {
        errors["staticAuth.username"] = "usernameRequired";
      }
      if (!isReference(staticAuth.passwordRef)) {
        errors["staticAuth.passwordRef"] = "secretMustBeReference";
      }
    } else if (binding === "CALLER_SUPPLIED") {
      // The inverse of the STATIC rule below. The value arrives with each
      // request, so a stored template is either dead config or a second
      // credential racing the supplied one — the backend refuses all three
      // fields rather than picking a winner by resolution order.
      if ((staticAuth.valueTemplate ?? "").trim()) {
        errors["staticAuth.valueTemplate"] = "callerSuppliedRefused";
      }
      if ((staticAuth.username ?? "").trim()) {
        errors["staticAuth.username"] = "callerSuppliedRefused";
      }
      if ((staticAuth.passwordRef ?? "").trim()) {
        errors["staticAuth.passwordRef"] = "callerSuppliedRefused";
      }
    } else {
      const problem = validateHeaderTemplate(staticAuth.valueTemplate);
      if (problem) errors["staticAuth.valueTemplate"] = problem;
    }
  } else {
    const oauth = config.oauth ?? {};
    const tokenUrl = validateCredentialEndpoint(oauth.tokenUrl, true);
    if (tokenUrl) errors["oauth.tokenUrl"] = tokenUrl;

    const discoveryUrl = validateCredentialEndpoint(oauth.discoveryUrl, false);
    if (discoveryUrl) errors["oauth.discoveryUrl"] = discoveryUrl;

    if (!(oauth.clientId ?? "").trim()) errors["oauth.clientId"] = "clientIdRequired";
    if (!isReference(oauth.clientSecret)) {
      errors["oauth.clientSecret"] = "secretMustBeReference";
    }
    if (authType === "OAUTH2_AUTHORIZATION_CODE") {
      const authorizationUrl = validateCredentialEndpoint(oauth.authorizationUrl, true);
      if (authorizationUrl) errors["oauth.authorizationUrl"] = authorizationUrl;
    }
    for (const [key, value] of Object.entries(oauth.extraAuthParams ?? {})) {
      const problem = isCredentialParamName(key)
        ? "paramCredentialShaped"
        : isReservedOAuthParamName(key)
          ? "paramReserved"
          : validateParamValue(value);
      if (problem) {
        errors["oauth.extraAuthParams"] = problem;
        break;
      }
    }
  }

  return errors;
}
