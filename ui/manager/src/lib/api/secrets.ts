/* ─── Types ─── */

export interface SecretMetadata {
  tenantId: string;
  agentId: string;
  keyName: string;
  createdAt: string;   // ISO instant
  lastAccessedAt: string | null;
  checksum: string;
}

export interface SecretStoreRequest {
  value: string;
}

export interface SecretStoreResponse {
  reference: string;
  tenantId: string;
  agentId: string;
  keyName: string;
}

export interface VaultHealth {
  status: "UP" | "DOWN";
  provider: string;
  available: boolean;
}

/* ─── API Functions ─── */

const BASE = "/secretstore/secrets";

/** List all secrets for a given tenant+agent namespace. */
export async function listSecrets(
  tenantId: string,
  agentId: string,
): Promise<SecretMetadata[]> {
  const res = await fetch(`${window.location.origin}${BASE}/${tenantId}/${agentId}`);
  if (!res.ok) return [];
  return res.json();
}

/** Store (create or update) a secret. */
export async function storeSecret(
  tenantId: string,
  agentId: string,
  keyName: string,
  value: string,
): Promise<SecretStoreResponse> {
  const res = await fetch(
    `${window.location.origin}${BASE}/${tenantId}/${agentId}/${keyName}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ value }),
    },
  );
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(err.error || `Failed to store secret (HTTP ${res.status})`);
  }
  return res.json();
}

/** Delete a secret from the vault. */
export async function deleteSecret(
  tenantId: string,
  agentId: string,
  keyName: string,
): Promise<void> {
  const res = await fetch(
    `${window.location.origin}${BASE}/${tenantId}/${agentId}/${keyName}`,
    { method: "DELETE" },
  );
  if (!res.ok && res.status !== 204) {
    const err = await res.json().catch(() => ({ error: "Unknown error" }));
    throw new Error(err.error || `Failed to delete secret (HTTP ${res.status})`);
  }
}

/** Get vault health status. */
export async function getVaultHealth(): Promise<VaultHealth> {
  const res = await fetch(`${window.location.origin}${BASE}/health`);
  return res.json();
}
