import { api } from "../api-client";

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
    `${api.getBaseUrl()}${BASE}/${tenantId}`,
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
    `${api.getBaseUrl()}${BASE}/${tenantId}/${keyName}`,
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

/** Delete a secret from the vault. */
export async function deleteSecret(
  tenantId: string,
  keyName: string,
): Promise<void> {
  const res = await fetch(
    `${api.getBaseUrl()}${BASE}/${tenantId}/${keyName}`,
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

/** Rotate a secret — store a new value and mark the rotation timestamp. */
export async function rotateSecret(
  tenantId: string,
  keyName: string,
  newValue: string,
  description?: string,
): Promise<SecretStoreResponse> {
  // The backend POST endpoint handles rotation (sets lastRotatedAt)
  const body: SecretStoreRequest = { value: newValue };
  if (description) body.description = description;

  const res = await fetch(
    `${api.getBaseUrl()}${BASE}/${tenantId}/${keyName}/rotate`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...api.getAuthHeader() },
      body: JSON.stringify(body),
    },
  );
  if (!res.ok) {
    // Ordered deliberately: a 503 is a configuration problem and must surface as
    // one, while a 404/405 only means this backend predates the rotate endpoint
    // and a plain PUT achieves the same thing.
    if (res.status === 404 || res.status === 405) {
      return storeSecret(tenantId, keyName, newValue, description);
    }
    await throwVaultError(res, "rotate secret");
  }
  return res.json();
}

/* ─── Key lifecycle (crypto-key operations) ─── */

/**
 * Rotate the Data Encryption Key (DEK) for a tenant.
 * SAFE: no restart required — re-encrypts all of the tenant's secrets with a
 * freshly generated DEK. Maps to `POST /{tenantId}/rotate-dek`.
 */
export async function rotateDek(tenantId: string): Promise<RotateDekResponse> {
  const res = await fetch(
    `${api.getBaseUrl()}${BASE}/${tenantId}/rotate-dek`,
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
    `${api.getBaseUrl()}${BASE}/${tenantId}/reset`,
    { method: "POST", headers: api.getAuthHeader() },
  );
  if (!res.ok) {
    await throwVaultError(res, "reset vault");
  }
  return res.json();
}
