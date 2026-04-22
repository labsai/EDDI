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

/** List all secrets for a given tenant. */
export async function listSecrets(
  tenantId: string,
): Promise<SecretMetadata[]> {
  const res = await fetch(
    `${api.getBaseUrl()}${BASE}/${tenantId}`,
    { headers: api.getAuthHeader() },
  );
  if (!res.ok) return [];
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
    if (res.status === 503) {
      throw new Error(
        "Secrets vault is not configured. Set up a secret provider in the EDDI backend.",
      );
    }
    const err = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(err.error || `Failed to store secret (HTTP ${res.status})`);
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
    if (res.status === 503) {
      throw new Error(
        "Secrets vault is not configured. Set up a secret provider in the EDDI backend.",
      );
    }
    const err = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(
      err.error || `Failed to delete secret (HTTP ${res.status})`,
    );
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
    if (res.status === 503) {
      throw new Error(
        "Secrets vault is not configured. Set up a secret provider in the EDDI backend.",
      );
    }
    // Fallback: if the rotate endpoint doesn't exist, use PUT
    if (res.status === 404 || res.status === 405) {
      return storeSecret(tenantId, keyName, newValue, description);
    }
    const err = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(err.error || `Failed to rotate secret (HTTP ${res.status})`);
  }
  return res.json();
}
