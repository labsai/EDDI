import { api, isApiError } from "../api-client";
import { bindingFor } from "../connection-validation";
import { parseResourceUri, type AgentDescriptor } from "./agents";

/**
 * Connections — how EDDI authenticates to an external system.
 *
 * **Two route groups with different audiences, deliberately not folded together.**
 *
 * - `/connectionstore/connections` is `eddi-admin` only and manages
 *   *configuration*: versioned CRUD, exactly like every other config store.
 * - `/connections` is any authenticated user and manages *their own
 *   credentials*: start a link, list what they have linked, unlink.
 *
 * The split is the backend's, and it is load-bearing. Folding them together
 * would mean either admins-only account linking — which defeats the point of a
 * per-user connection — or an admin-scoped path any user can reach.
 *
 * ## What is deliberately absent
 *
 * There is no endpoint that returns a resolved credential and none that returns
 * a grant. A connection document carries only *references*, so reading one is
 * safe; a grant carries tokens, so it has no read surface at all. `/connections/mine`
 * returns a hand-enumerated view — name, status, expiry, scopes — never token
 * material. Nothing here should ever be extended to ask for more.
 *
 * ## Same-origin is a requirement, not a default
 *
 * `authorize` is answered with a `Set-Cookie` nonce that binds the flow to this
 * browser, and the provider's callback must arrive carrying it. That works
 * because the Manager is served from the backend's own origin (and, in dev,
 * through the Vite proxy, which is the same thing as far as the browser is
 * concerned).
 *
 * A cross-origin deployment cannot be made to work from this side alone. It
 * would need `credentials: "include"` here **and** CORS configured to allow
 * credentials **and** the backend's cookie relaxed from `SameSite=Lax` to
 * `SameSite=None; Secure` — browsers reject a `Lax` cookie set from a
 * cross-site XHR response. Without all three, every callback fails with
 * `invalid_state` and nothing explains why. Adding `credentials: "include"`
 * alone would look like a fix and change nothing.
 */

// ─── Types ──────────────────────────────────────────────────────

export const AUTH_TYPES = [
  "STATIC",
  "BASIC",
  "OAUTH2_CLIENT_CREDENTIALS",
  "OAUTH2_AUTHORIZATION_CODE",
] as const;
export type AuthType = (typeof AUTH_TYPES)[number];

/**
 * Whose credential a connection resolves.
 *
 * Not offered as a choice in the UI: the backend couples it to `authType` in
 * both directions, which leaves exactly one legal value per type. See
 * `bindingFor` in `connection-validation.ts`.
 */
export const BINDINGS = ["SERVICE", "PER_USER"] as const;
export type Binding = (typeof BINDINGS)[number];

export const CLIENT_AUTH_METHODS = [
  "client_secret_basic",
  "client_secret_post",
] as const;
export type ClientAuthMethod = (typeof CLIENT_AUTH_METHODS)[number];

export interface StaticAuth {
  /** `Authorization`, `X-Api-Key`, … Non-secret by nature. */
  headerName: string;
  /** e.g. `Bearer ${vault:jira-token}`. STATIC only. */
  valueTemplate?: string | null;
  /** BASIC only. An identifier, not a secret. */
  username?: string | null;
  /** BASIC only. Must be a `${vault:…}` reference. */
  passwordRef?: string | null;
}

export interface OAuthConfig {
  /** Null for `OAUTH2_CLIENT_CREDENTIALS`. */
  authorizationUrl?: string | null;
  tokenUrl?: string | null;
  clientId?: string | null;
  /** Must be a `${vault:…}` reference — never a literal. */
  clientSecret?: string | null;
  scopes?: string[] | null;
  /** Non-secret protocol parameters only (`prompt`, `audience`, …). */
  extraAuthParams?: Record<string, string> | null;
  /**
   * Required true for `OAUTH2_AUTHORIZATION_CODE` — the callback is a public
   * path, and without PKCE it is an authorization-code interception vector.
   * Round-tripped so a config survives a save, never offered as a switch.
   */
  usePkce?: boolean;
  clientAuthMethod?: string | null;
  discoveryUrl?: string | null;
}

export interface ConnectionConfiguration {
  /** Referenced as `${connection:name}`. Immutable after create. */
  name: string;
  description?: string | null;
  authType: AuthType;
  binding: Binding;
  allowUnverifiedPrincipal: boolean;
  staticAuth?: StaticAuth | null;
  oauth?: OAuthConfig | null;
  /** Origins the credential may be sent to. Required, bare origins only. */
  baseUrlAllowlist: string[];
  timeoutMs?: number | null;
  /**
   * Present only when the backend sent one.
   *
   * Never written from here: the store refuses any tenant but `default` at the
   * write boundary, because the per-user endpoints are still scoped to it — a
   * grant filed under another tenant could be neither listed nor revoked. The
   * field is round-tripped so a document read back is not silently altered, and
   * the form does not surface it.
   */
  tenantId?: string;
}

export type ConnectionDescriptor = AgentDescriptor;

/** A grant's lifecycle state, as `/connections/mine` reports it. */
export const GRANT_STATUSES = [
  "ACTIVE",
  "EXPIRED",
  "REVOKED",
  "REFRESH_FAILED",
] as const;
export type GrantStatus = (typeof GRANT_STATUSES)[number];

/**
 * One linked account. Never carries token material — the backend enumerates
 * these fields by hand rather than serializing the grant entity.
 */
export interface LinkedAccount {
  connection: string;
  status: GrantStatus;
  /** ISO instant, or null when the provider issued no expiry. */
  expiresAt: string | null;
  scopes: string[] | null;
  connectedAt: string | null;
}

/**
 * The closed set of outcomes the callback can redirect back with.
 *
 * That is all there is. The provider's own `error_description` is not
 * forwarded — it is not even bound on the callback, precisely so that no later
 * change can start echoing attacker-influenceable text into a browser. There is
 * therefore no provider-supplied detail to fall back on and no "show technical
 * details" affordance to build: the Manager owns every word the user reads
 * here, and the operator-facing detail is in the server log.
 *
 * `invalid_state` is deliberately overloaded — unknown, expired, already-used
 * and failed-browser-binding are answered identically, because telling them
 * apart is a state-guessing oracle. One honest message has to cover all four.
 */
export const CONNECTION_ERROR_CODES = [
  "invalid_state",
  "authorization_declined",
  "missing_code",
  "connection_removed",
  "exchange_failed",
] as const;
export type ConnectionErrorCode = (typeof CONNECTION_ERROR_CODES)[number];

export function isConnectionErrorCode(
  value: string | null,
): value is ConnectionErrorCode {
  return (
    value !== null &&
    (CONNECTION_ERROR_CODES as readonly string[]).includes(value)
  );
}

// ─── Errors ─────────────────────────────────────────────────────

/**
 * Account linking is switched off on this deployment.
 *
 * A stable code rather than a sentence, for the reason `secrets.ts` gives: this
 * layer has no React context and cannot call `t()`, so it raises a code and the
 * UI translates it. Throwing English from here is how the vault came to fail in
 * English in all eleven locales.
 */
export const CONNECTIONS_DISABLED = "CONNECTIONS_DISABLED";

/** No verified identity — authorization is off, or the caller is anonymous. */
export const CONNECTIONS_FORBIDDEN = "CONNECTIONS_FORBIDDEN";

/** An error carrying a translatable code alongside its fallback message. */
export class ConnectionsError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "ConnectionsError";
  }
}

/**
 * Re-throw a per-user route failure with a code the UI can translate.
 *
 * The 404 is the one worth converting. `requireEnabled()` answers a disabled
 * feature with 404 (not 503 — a 503 body is dropped by the backend's exception
 * mapper, so the sentence naming the setting never arrived), and a bare 404
 * would otherwise render as "not found" on a page that is very much found. The
 * same code covers a backend too old to have these routes at all, which is the
 * same fact from the user's side.
 */
function asConnectionsError(
  error: unknown,
  action: string,
  { notFoundMeansDisabled = true }: { notFoundMeansDisabled?: boolean } = {},
): never {
  if (isApiError(error)) {
    if ((error.status === 404 && notFoundMeansDisabled) || error.status === 503) {
      throw new ConnectionsError(
        "Account linking is not enabled on this deployment.",
        CONNECTIONS_DISABLED,
        error.status,
      );
    }
    if (error.status === 403 || error.status === 401) {
      throw new ConnectionsError(
        "Linking an account requires a signed-in user.",
        CONNECTIONS_FORBIDDEN,
        error.status,
      );
    }
    throw new ConnectionsError(error.message, undefined, error.status);
  }
  throw new ConnectionsError(
    error instanceof Error ? error.message : `Failed to ${action}`,
  );
}

// ─── Admin CRUD (eddi-admin) ────────────────────────────────────

const STORE = "/connectionstore/connections";

export function getConnectionDescriptors(
  limit = 20,
  index = 0,
  filter = "",
): Promise<ConnectionDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<ConnectionDescriptor[]>(
    `${STORE}/descriptors?${params.toString()}`,
  );
}

export function getConnection(
  id: string,
  version?: number,
): Promise<ConnectionConfiguration> {
  const suffix = version !== undefined ? `?version=${version}` : "";
  return api.get<ConnectionConfiguration>(`${STORE}/${id}${suffix}`);
}

export function createConnection(
  config: ConnectionConfiguration,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(STORE, config);
}

export function updateConnection(
  id: string,
  version: number,
  config: ConnectionConfiguration,
): Promise<{ location: string }> {
  return api.put(`${STORE}/${id}?version=${version}`, config);
}

export function deleteConnection(
  id: string,
  version: number,
  permanent = true,
): Promise<void> {
  const params = new URLSearchParams({
    version: String(version),
    permanent: String(permanent),
  });
  return api.delete(`${STORE}/${id}?${params}`);
}

export function duplicateConnection(
  id: string,
  version: number,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(`${STORE}/${id}?version=${version}`);
}

// ─── Per-user grants (any signed-in user) ───────────────────────

/**
 * Start an authorization-code flow for the calling user.
 *
 * The response carries the provider URL *and* a `Set-Cookie` nonce binding the
 * flow to this browser. The caller must then perform a **top-level navigation**
 * — not a popup, not an iframe: the cookie is `SameSite=Lax`, which admits a
 * top-level GET return and nothing else.
 *
 * @param returnTo where to send the browser afterwards. Same-origin only, and
 *   validated server-side; anything it refuses falls back to the deployment's
 *   default page (`/manage/connections`) rather than failing.
 */
export async function authorizeConnection(
  name: string,
  returnTo: string,
): Promise<{ authorizationUrl: string }> {
  try {
    return await api.post<{ authorizationUrl: string }>(
      `/connections/${encodeURIComponent(name)}/authorize?returnTo=${encodeURIComponent(returnTo)}`,
    );
  } catch (error) {
    // A 404 here is ambiguous in a way it is not on `/connections/mine`: the
    // backend's `requireConnection` throws NotFoundException for "no connection
    // named X" from the same route that answers 404 when the feature is off.
    // Claiming "linking is switched off" for a connection an admin just deleted
    // sends the operator to check a setting that is already correct, so the
    // backend's own message is passed through instead.
    return asConnectionsError(error, "start linking", {
      notFoundMeansDisabled: false,
    });
  }
}

/** The calling user's linked accounts. Never includes tokens. */
export async function listMyConnections(): Promise<LinkedAccount[]> {
  try {
    return await api.get<LinkedAccount[]>("/connections/mine");
  } catch (error) {
    return asConnectionsError(error, "list linked accounts");
  }
}

/**
 * Unlink the calling user's own account.
 *
 * Resolves by name and does **not** require the connection to still exist. That
 * is the case that matters most: an admin deletes a connection while automatic
 * cleanup is failing, and the user must not be left holding a live refresh
 * token with no way to revoke it. Unlinking is never harder than linking was.
 *
 * Answers 204 whether or not a grant existed, so a successful call is not
 * evidence that one did.
 */
export async function disconnectConnection(name: string): Promise<void> {
  try {
    await api.delete<void>(
      `/connections/${encodeURIComponent(name)}/grant`,
    );
  } catch (error) {
    return asConnectionsError(error, "unlink account");
  }
}

// ─── Helpers ────────────────────────────────────────────────────

/**
 * Re-exported under a connection-specific name so call sites read naturally.
 *
 * The parser itself is `parseResourceUri` from `agents.ts` — the shape is
 * store-agnostic (strip `eddi://`, take the last path segment, read `version`),
 * and this module previously carried a third character-for-character copy of
 * it. A URI-shape fix applied to one copy and not the others resolves the wrong
 * id or silently falls back to version 1, which is a save against the wrong
 * revision with a green test suite.
 */
export { parseResourceUri as parseConnectionResourceUri } from "./agents";

/** A descriptor enriched with the config-level facts the list renders. */
export type EnrichedConnectionDescriptor = ConnectionDescriptor & {
  id: string;
  version: number;
  /** The `name` a `${connection:…}` reference points at — not the descriptor's. */
  connectionName: string;
  authType: AuthType | "unknown";
  binding: Binding | "unknown";
  origins: string[];
  /** Null when the config could not be read; the card says so rather than lying. */
  unreadable: boolean;
  /**
   * The document this row was enriched from.
   *
   * Carried so the hook can seed the detail page's cache with it: the list
   * already paid for the GET, and throwing the body away means clicking a row
   * re-downloads what was in memory a moment ago. Absent when the read failed.
   */
  config?: ConnectionConfiguration;
};

/**
 * Fetch descriptors and enrich them from the full configs.
 *
 * Same shape as `getEnrichedChannelDescriptors`: deduplicate by id keeping the
 * latest version, then batch-fetch. A config that fails to read degrades to an
 * `unreadable` row rather than dropping out of the list — a connection the
 * admin cannot see is one they cannot delete either.
 */
export async function getEnrichedConnectionDescriptors(
  limit = 20,
  index = 0,
  filter = "",
): Promise<EnrichedConnectionDescriptor[]> {
  const descriptors = await getConnectionDescriptors(limit, index, filter);

  const grouped = new Map<
    string,
    ConnectionDescriptor & { id: string; version: number }
  >();
  for (const d of descriptors) {
    const { id, version } = parseResourceUri(d.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...d, id, version });
    }
  }

  return Promise.all(
    Array.from(grouped.values())
      .sort((a, b) => b.lastModifiedOn - a.lastModifiedOn)
      .map(async (d) => {
        try {
          const config = await getConnection(d.id, d.version);
          return {
            ...d,
            name: config.name || d.name,
            connectionName: config.name,
            authType: config.authType,
            binding: config.binding,
            origins: config.baseUrlAllowlist ?? [],
            unreadable: false,
            config,
          };
        } catch {
          return {
            ...d,
            connectionName: d.name,
            authType: "unknown" as const,
            binding: "unknown" as const,
            origins: [],
            unreadable: true,
          };
        }
      }),
  );
}

/**
 * The document to store, built from the auth type rather than from the draft.
 *
 * An editor keeps both auth blocks in its draft so a mis-clicked type switch is
 * reversible. Only the fields the chosen type actually uses may be *stored*,
 * and "the right block" is not enough granularity: `staticAuth` is used by both
 * STATIC and BASIC, so switching BASIC → STATIC and saving kept `username` and
 * a `${vault:…}` `passwordRef` on a connection whose flow reads neither.
 *
 * That is not cosmetic. It persists a pointer to a credential the connection
 * has no reason to hold, shows it in the raw panel, and re-arms it if the type
 * is ever switched back — a value the author never re-confirmed. The same
 * applies to `oauth.authorizationUrl` on a client-credentials connection, where
 * the field is not even rendered.
 *
 * Enumerating per type also means a field added to one flow cannot leak into
 * another by omission: anything not listed here is not sent.
 */
export function toStoredConnection(
  draft: ConnectionConfiguration,
): ConnectionConfiguration {
  const base: ConnectionConfiguration = {
    ...draft,
    binding: bindingFor(draft.authType),
    // Only legal on a per-user binding; the backend refuses it elsewhere rather
    // than ignoring it.
    allowUnverifiedPrincipal:
      draft.authType === "OAUTH2_AUTHORIZATION_CODE"
        ? draft.allowUnverifiedPrincipal
        : false,
    staticAuth: null,
    oauth: null,
  };

  if (draft.authType === "STATIC") {
    return {
      ...base,
      staticAuth: {
        headerName: draft.staticAuth?.headerName ?? "",
        valueTemplate: draft.staticAuth?.valueTemplate ?? "",
      },
    };
  }
  if (draft.authType === "BASIC") {
    return {
      ...base,
      staticAuth: {
        headerName: draft.staticAuth?.headerName ?? "",
        username: draft.staticAuth?.username ?? "",
        passwordRef: draft.staticAuth?.passwordRef ?? "",
      },
    };
  }

  const oauth = draft.oauth ?? {};
  const common: OAuthConfig = {
    tokenUrl: oauth.tokenUrl ?? "",
    clientId: oauth.clientId ?? "",
    clientSecret: oauth.clientSecret ?? "",
    scopes: oauth.scopes ?? [],
    extraAuthParams: oauth.extraAuthParams ?? {},
    clientAuthMethod: oauth.clientAuthMethod ?? "client_secret_basic",
    discoveryUrl: oauth.discoveryUrl ?? null,
  };

  if (draft.authType === "OAUTH2_AUTHORIZATION_CODE") {
    return {
      ...base,
      oauth: {
        ...common,
        authorizationUrl: oauth.authorizationUrl ?? "",
        // Not switchable: the callback is a public path, and the backend
        // refuses an authorization-code connection without it.
        usePkce: true,
      },
    };
  }
  return { ...base, oauth: { ...common, authorizationUrl: null } };
}

/**
 * A blank connection of the given type, valid apart from the fields the author
 * must supply.
 *
 * `binding` is derived, never defaulted independently — a `SERVICE` binding on
 * an authorization-code connection saves cleanly and then resolves every call
 * against a principal no flow can ever produce a grant for.
 */
export function emptyConnection(authType: AuthType): ConnectionConfiguration {
  const base: ConnectionConfiguration = {
    name: "",
    description: "",
    authType,
    binding: bindingFor(authType),
    allowUnverifiedPrincipal: false,
    baseUrlAllowlist: [],
    staticAuth: null,
    oauth: null,
  };
  if (authType === "STATIC" || authType === "BASIC") {
    return {
      ...base,
      staticAuth: {
        headerName: "Authorization",
        valueTemplate: authType === "STATIC" ? "" : null,
        username: authType === "BASIC" ? "" : null,
        passwordRef: authType === "BASIC" ? "" : null,
      },
    };
  }
  return {
    ...base,
    oauth: {
      authorizationUrl: authType === "OAUTH2_AUTHORIZATION_CODE" ? "" : null,
      tokenUrl: "",
      clientId: "",
      clientSecret: "",
      scopes: [],
      extraAuthParams: {},
      usePkce: true,
      clientAuthMethod: "client_secret_basic",
      discoveryUrl: null,
    },
  };
}
