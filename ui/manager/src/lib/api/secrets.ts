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
    `${window.location.origin}${BASE}/${tenantId}`,
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
    `${window.location.origin}${BASE}/${tenantId}/${keyName}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
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
    `${window.location.origin}${BASE}/${tenantId}/${keyName}`,
    { method: "DELETE" },
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
    const res = await fetch(`${window.location.origin}${BASE}/health`);
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
