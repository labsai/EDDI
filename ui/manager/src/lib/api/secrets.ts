import { api, ApiClientError } from "../api-client";

/* ─── Types ─── */

export interface SecretMetadata {
  tenantId: string;
  keyName: string;
  createdAt: string; // ISO instant
  lastAccessedAt: string | null;
  lastRotatedAt: string | null;
  checksum: string;
  description: string | null;
  allowedAgents: string[]; // ["*"] = all agents
}

export interface SecretStoreRequest {
  value: string;
  description?: string;
  allowedAgents?: string[];
}

export interface SecretStoreResponse {
  reference: string;
  tenantId: string;
  keyName: string;
}

/** The wildcard that grants a secret to every agent. */
export const ALL_AGENTS = "*";

/**
 * Whether a grant list leaves the secret open to every agent.
 *
 * Three shapes mean that and the backend treats all three alike: the wildcard,
 * an empty list, and an absent one. Wherever this gets decided by hand one of the
 * three is forgotten, and a secret then reads as narrow while behaving as open.
 */
export function grantsAllAgents(allowedAgents?: string[] | null): boolean {
  return (
    !allowedAgents ||
    allowedAgents.length === 0 ||
    allowedAgents.includes(ALL_AGENTS)
  );
}

/**
 * A deployed agent that references a secret and would not be on its new grant
 * list. It keeps running — the grant is checked when an agent is deployed, not
 * when a secret is resolved — but its next deployment is refused while
 * `eddi.vault.grant-enforcement=enforce`.
 */
export interface AffectedAgent {
  agentId: string;
  agentVersion: number | null;
  environment: string;
}

/** The body a grant edit sends. Note the absence of a `value` field. */
export interface SecretGrantRequest {
  /** Required — never omitted, see `updateSecretGrant`. `["*"]` = every agent. */
  allowedAgents: string[];
  /** Omitted to leave the existing description alone. */
  description?: string;
}

export interface SecretGrantResponse {
  reference: string;
  tenantId: string;
  keyName: string;
  dryRun: boolean;
  allowedAgents: string[];
  previousAllowedAgents: string[];
  grantsAllAgents: boolean;
  /** Omitted rather than null when unset — EDDI's REST mapper drops null fields. */
  description?: string;
  /** Echoed back unchanged — a grant edit is not a rotation. Omitted when null. */
  createdAt?: string;
  lastRotatedAt?: string;
  agentsLosingAccess: AffectedAgent[];
  /**
   * False when the backend could not list every environment, so the list above may
   * be short. Absent on an older backend, which is why callers test `=== false`
   * rather than falsiness — an absent flag is not a failed scan.
   */
  agentsLosingAccessComplete?: boolean;
  /** Which node's deployments the list covers. */
  agentsLosingAccessScope?: string;
  /** Only present when `agentsLosingAccess` is non-empty. */
  warning?: string;
}

/** Response of a per-tenant DEK rotation (safe, no restart). */
export interface RotateDekResponse {
  tenantId: string;
  /** Number of secrets re-encrypted with the freshly generated DEK. */
  secretsReEncrypted: number;
  message: string;
}

/** Response of a master-key (KEK) rotation. */
export interface RotateKekResponse {
  /** Number of tenant DEKs re-encrypted with the new master key. */
  deksReEncrypted: number;
  message: string;
}

/** Response of a destructive per-tenant vault reset. */
export interface ResetTenantResponse {
  tenantId: string;
  /** Number of secrets permanently deleted. */
  secretsDeleted: number;
  message: string;
}

export interface VaultHealth {
  status: "UP" | "DOWN";
  provider: string;
  available: boolean;
  /** Present when 503 — human-readable error title */
  error?: string;
  /** Present when 503 — why the vault is unavailable */
  reason?: string;
  /** Present when 503 — how to fix it */
  action?: string;
  /** Present when 503 — documentation URL */
  docs?: string;
}

/* ─── API Functions ─── */

const BASE = "/secretstore/secrets";

/**
 * `/{tenantId}/{keyName}` with both segments percent-encoded.
 *
 * Both are free text typed into the Manager. Spliced in raw, a key called
 * `a/b` addressed `…/a/b` — a different resource — and one containing `?`
 * turned the rest of the name into a query string, so the PUT or DELETE landed
 * on something the operator never named. `updateSecretGrant` already encoded
 * its segments; the raw `fetch` calls here did not.
 */
function secretPath(tenantId: string, keyName?: string): string {
  const tenant = `${BASE}/${encodeURIComponent(tenantId)}`;
  return keyName === undefined ? tenant : `${tenant}/${encodeURIComponent(keyName)}`;
}

/**
 * Stable code for "the backend has no secret provider configured".
 *
 * The API layer cannot call `t()` — it has no React context — so it raises a
 * code and the UI translates it. Before this, seven copies of one English
 * sentence were thrown straight at the user from here, which in an app that
 * ships eleven locales meant the vault always failed in English.
 */
export const VAULT_NOT_CONFIGURED = "VAULT_NOT_CONFIGURED";

/** An error carrying a translatable code alongside its fallback message. */
export class SecretsError extends Error {
  constructor(
    message: string,
    readonly code?: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "SecretsError";
  }
}

/**
 * Turn a non-OK vault response into a thrown `SecretsError`.
 *
 * Every call in this module needs the identical four steps — special-case 503,
 * otherwise read the body's `error` field, otherwise fall back to the status.
 * They were copy-pasted seven times; one of the copies is how a past bug
 * swallowed vault failures into an empty state.
 *
 * @param action verb for the fallback message, e.g. "list secrets"
 */
async function throwVaultError(res: Response, action: string): Promise<never> {
  if (res.status === 503) {
    throw new SecretsError(
      "Secrets vault is not configured. Set up a secret provider in the EDDI backend.",
      VAULT_NOT_CONFIGURED,
      503,
    );
  }
  const body = await res.json().catch(() => ({}) as { error?: string });
  throw new SecretsError(
    body.error || `Failed to ${action} (HTTP ${res.status})`,
    undefined,
    res.status,
  );
}

/** List all secrets for a given tenant. */
export async function listSecrets(
  tenantId: string,
): Promise<SecretMetadata[]> {
  const res = await fetch(
    `${api.getBaseUrl()}${secretPath(tenantId)}`,
    { headers: api.getAuthHeader() },
  );
  // Throw on non-OK so callers can distinguish a real failure (500/503/403)
  // from a genuinely empty vault ([]). Swallowing errors here made every
  // backend failure render as the misleading "No secrets found" empty state.
  if (!res.ok) {
    await throwVaultError(res, "list secrets");
  }
  return res.json();
}

/** Store (create or update) a secret. */
export async function storeSecret(
  tenantId: string,
  keyName: string,
  value: string,
  description?: string,
  allowedAgents?: string[],
): Promise<SecretStoreResponse> {
  const body: SecretStoreRequest = { value };
  if (description) body.description = description;
  if (allowedAgents) body.allowedAgents = allowedAgents;

  const res = await fetch(
    `${api.getBaseUrl()}${secretPath(tenantId, keyName)}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json", ...api.getAuthHeader() },
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) {
    await throwVaultError(res, "store secret");
  }
  return res.json();
}

/**
 * Change which agents may use a secret, without re-supplying its value.
 *
 * `PUT /{tenantId}/{keyName}/grant`. Distinct from `storeSecret` in the one way
 * that matters: it carries no value, so it cannot be the reason a secret gets
 * overwritten or blanked. It is also the only way to widen a grant at all once a
 * key is vaulted, because the plaintext `storeSecret` insists on is by then gone.
 *
 * `allowedAgents` is always sent, even when it is `["*"]`. The backend rejects an
 * omitted list rather than defaulting it, precisely so a dropped field cannot
 * open a narrowed secret to everything.
 *
 * @param dryRun writes nothing and returns what the change *would* do — this is
 *   what feeds the "these deployed agents lose access" warning before the
 *   operator commits rather than after.
 */
export async function updateSecretGrant(args: {
  tenantId: string;
  keyName: string;
  allowedAgents: string[];
  description?: string;
  dryRun?: boolean;
}): Promise<SecretGrantResponse> {
  const body: SecretGrantRequest = { allowedAgents: args.allowedAgents };
  // Undefined means "keep the existing description"; an empty string clears it,
  // so `!== undefined` rather than a truthiness check.
  if (args.description !== undefined) body.description = args.description;

  // Through ApiClient rather than the raw `fetch` the rest of this module still
  // uses (AGENTS.md names that as debt): it attaches auth, and turns the backend's
  // `{"error": …}` body into the error message with the status kept.
  const query = args.dryRun ? "?dryRun=true" : "";
  return api.put<SecretGrantResponse>(`${secretPath(args.tenantId, args.keyName)}/grant${query}`, body);
}

/** Delete a secret from the vault. */
export async function deleteSecret(
  tenantId: string,
  keyName: string,
): Promise<void> {
  const res = await fetch(
    `${api.getBaseUrl()}${secretPath(tenantId, keyName)}`,
    { method: "DELETE", headers: api.getAuthHeader() },
  );
  if (!res.ok && res.status !== 204) {
    await throwVaultError(res, "delete secret");
  }
}

/** Get vault health status. Parses the body on both 200 and 503. */
export async function getVaultHealth(): Promise<VaultHealth> {
  try {
    const res = await fetch(`${api.getBaseUrl()}${BASE}/health`, {
      headers: api.getAuthHeader(),
    });
    const data = await res.json();
    if (res.status === 503) {
      // Backend returns { error, reason, action, docs } on 503
      return {
        status: "DOWN",
        provider: data.provider ?? "unknown",
        available: false,
        error: data.error,
        reason: data.reason,
        action: data.action,
        docs: data.docs,
      };
    }
    return data;
  } catch {
    return { status: "DOWN", provider: "unknown", available: false };
  }
}

/**
 * Replace a secret's value, keeping who may use it.
 *
 * There is no rotate endpoint: EDDI rotates by storing a new value under the
 * same key (`lastRotatedAt` moves on any PUT to an existing key). The Manager
 * used to POST to a `/rotate` path that has never existed and fall back to a
 * bare PUT on the 404 — and on a backend before the vault-key-safety fix, a PUT
 * without `allowedAgents` stores `["*"]` and a blank description. Every
 * rotation from this screen opened a narrowed secret to every agent.
 *
 * So the grant and description go along explicitly, taken from `current`,
 * which the caller must have read just before (`useRotateSecret` re-reads it).
 * A newer backend keeps both when they are omitted, so sending them changes
 * nothing there; on the older one it is the only thing that preserves them.
 */
export async function rotateSecret(
  tenantId: string,
  keyName: string,
  newValue: string,
  current: Pick<SecretMetadata, "allowedAgents" | "description">,
): Promise<SecretStoreResponse> {
  // An empty or missing list means "every agent" to the backend, exactly like
  // the wildcard — send the wildcard rather than a list it could read either way.
  const allowedAgents = grantsAllAgents(current.allowedAgents)
    ? [ALL_AGENTS]
    : current.allowedAgents;
  return storeSecret(
    tenantId,
    keyName,
    newValue,
    current.description ?? undefined,
    allowedAgents,
  );
}

/**
 * Stable code for "this key is already in the vault" on a create.
 *
 * The store endpoint is an upsert with no create-only mode, so "Add Secret"
 * typed over an existing name replaced a live credential — and, on an older
 * backend, reset its grant to every agent — behind a green toast.
 */
export const SECRET_EXISTS = "SECRET_EXISTS";

/**
 * Stable code for a rotation whose key is no longer in the vault. Rotation is
 * a PUT, and a PUT creates: rotating a key someone deleted in the meantime
 * would bring it back open to every agent.
 */
export const SECRET_NOT_FOUND = "SECRET_NOT_FOUND";

/**
 * The key's current metadata, read fresh, or null when it does not exist.
 *
 * A list read rather than a per-key GET because the list is what the backend
 * exposes to the Manager for metadata; it is one tenant's secrets, not a page.
 */
export async function findSecret(
  tenantId: string,
  keyName: string,
): Promise<SecretMetadata | null> {
  const all = await listSecrets(tenantId);
  return all.find((s) => s.keyName === keyName) ?? null;
}

/* ─── Key lifecycle (crypto-key operations) ─── */

/**
 * Rotate the Data Encryption Key (DEK) for a tenant.
 * SAFE: no restart required — re-encrypts all of the tenant's secrets with a
 * freshly generated DEK. Maps to `POST /{tenantId}/rotate-dek`.
 */
export async function rotateDek(tenantId: string): Promise<RotateDekResponse> {
  const res = await fetch(
    `${api.getBaseUrl()}${secretPath(tenantId)}/rotate-dek`,
    { method: "POST", headers: api.getAuthHeader() },
  );
  if (!res.ok) {
    await throwVaultError(res, "rotate DEK");
  }
  return res.json();
}

/**
 * Rotate the master key (KEK). Re-encrypts every tenant DEK with the new master
 * key. Maps to `POST /admin/rotate-kek` with body `{ oldMasterKey, newMasterKey }`.
 *
 * WARNING: both keys are transmitted in the request body — only call over TLS.
 * After success, update `EDDI_VAULT_MASTER_KEY` to the new value and restart.
 */
export async function rotateKek(args: {
  oldKey: string;
  newKey: string;
}): Promise<RotateKekResponse> {
  const res = await fetch(
    `${api.getBaseUrl()}${BASE}/admin/rotate-kek`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...api.getAuthHeader() },
      body: JSON.stringify({
        oldMasterKey: args.oldKey,
        newMasterKey: args.newKey,
      }),
    },
  );
  if (!res.ok) {
    await throwVaultError(res, "rotate master key");
  }
  return res.json();
}

/**
 * Reset the vault for a tenant. DESTRUCTIVE: permanently deletes ALL secrets and
 * the DEK for the tenant so the vault can start fresh with the current master
 * key. Maps to `POST /{tenantId}/reset`. Use this as the recovery step after a
 * lost or changed master key.
 */
export async function resetTenant(
  tenantId: string,
): Promise<ResetTenantResponse> {
  const res = await fetch(
    `${api.getBaseUrl()}${secretPath(tenantId)}/reset`,
    { method: "POST", headers: api.getAuthHeader() },
  );
  if (!res.ok) {
    await throwVaultError(res, "reset vault");
  }
  return res.json();
}

/** Response of `POST /admin/adopt-master-key`. */
export interface AdoptMasterKeyResponse {
  /** Tenants whose DEKs the adopted key cannot open — each needs a reset. */
  tenantsNeedingReset: string[];
  /** Whether the reserved system tenant's sealed values had to be discarded. */
  systemValuesReset: boolean;
  message: string;
}

/**
 * Stable code for a backend with no adopt-master-key operation.
 *
 * An EDDI without it has no KEK check value either, so a lost key never blocks
 * new secrets there and resetting the affected tenant is the whole recovery.
 * The UI says so instead of reporting a bare 404.
 */
export const ADOPT_NOT_SUPPORTED = "ADOPT_NOT_SUPPORTED";

/**
 * Make the running master key the vault's master key after the previous one was
 * LOST. Maps to `POST /admin/adopt-master-key?confirm=true`.
 *
 * Destructive for anything sealed under the lost key. A backend that records
 * which key the vault uses refuses every new secret after a key change, because
 * a lost key and a replica that was not restarted look the same from a node;
 * this is the operator saying which case it is. The answer names the tenants
 * that must then be reset. Never the right call while a KEK rotation is merely
 * unfinished — re-running `rotateKek` with the same two keys recovers
 * everything there.
 */
export async function adoptMasterKey(): Promise<AdoptMasterKeyResponse> {
  try {
    const data = await api.post<Partial<AdoptMasterKeyResponse> | undefined>(
      `${BASE}/admin/adopt-master-key?confirm=true`,
    );
    return {
      tenantsNeedingReset: data?.tenantsNeedingReset ?? [],
      systemValuesReset: data?.systemValuesReset ?? false,
      message: data?.message ?? "",
    };
  } catch (err) {
    if (err instanceof ApiClientError && (err.status === 404 || err.status === 405)) {
      throw new SecretsError(
        "This EDDI version has no adopt-master-key step. Reset the affected tenant instead.",
        ADOPT_NOT_SUPPORTED,
        err.status,
      );
    }
    if (err instanceof ApiClientError && err.status === 503) {
      throw new SecretsError(
        "Secrets vault is not configured. Set up a secret provider in the EDDI backend.",
        VAULT_NOT_CONFIGURED,
        503,
      );
    }
    throw err;
  }
}
