import { preflightDeploy } from "@eddi/app-kit/lib/api/agents";
import {
  findSecret,
  grantAgentToSecret,
  grantsAllAgents,
  listSecrets,
  type SecretMetadata,
} from "@eddi/app-kit/lib/api/secrets";

/**
 * Carry the vault grants of the operator agent an upgrade (or reconfigure)
 * replaces over to the agent that replaces it, BEFORE the new one is deployed.
 *
 * Why: `setup-api` only creates, so every upgrade builds a new agent id, and a
 * grant lists agent ids. The old operator was on its model key's grant; the new
 * one was not, so every upgrade stopped mid-way on the grant dialog — whose one
 * tempting way out, "Allow every agent", removes the key's only limit. The new
 * agent is the same operator with the same settings, so it gets exactly what
 * its predecessor held, and nothing else.
 *
 * What it covers, and why only that:
 * - Every secret of the default tenant whose grant names the predecessor
 *   explicitly (`listSecrets("default")`).
 * - Any secret, in any tenant, that the NEW agent references and is not granted
 *   (the deploy preflight's issues) — read fresh, and carried only when its grant
 *   names the predecessor.
 *   The Manager cannot list tenants, so a grant in some other tenant that the
 *   new agent does not reference is not found; nothing needs it to deploy.
 *
 * Never widens: each change is the backend's one-agent append
 * (`POST …/grant/agents/{id}`), never a list replacement and never `*`; a
 * wildcard grant already covers the new agent and is left alone.
 *
 * The caller decides whether the predecessor may be trusted at all: the id comes
 * from `platform.operator`, which an editor can write, and copying the grants of
 * whatever agent an editor names would be a way to hand them out.
 *
 * Best-effort and silent on failure: a non-admin (403), a vault that is off, or
 * a backend without the preflight simply carries nothing, and the deploy that
 * follows asks through the grant dialog exactly as before.
 *
 * @returns the references (`tenant/key`) now granted to `toAgentId`
 */
export async function carryOverVaultGrants(params: {
  fromAgentId: string;
  toAgentId: string;
  version: number;
  environment: string;
}): Promise<string[]> {
  const { fromAgentId, toAgentId, version, environment } = params;
  if (!fromAgentId || !toAgentId || fromAgentId === toAgentId) return [];

  const candidates = new Map<string, { tenantId: string; keyName: string }>();
  const consider = (secret: Pick<SecretMetadata, "tenantId" | "keyName" | "allowedAgents"> | null) => {
    if (!secret || grantsAllAgents(secret.allowedAgents) || !secret.allowedAgents.includes(fromAgentId)) return;
    candidates.set(`${secret.tenantId}/${secret.keyName}`, { tenantId: secret.tenantId, keyName: secret.keyName });
  };

  try {
    for (const secret of await listSecrets("default")) consider(secret);
  } catch {
    // Not readable (403, vault off): the preflight below may still find what the deploy needs.
  }

  try {
    const preflight = await preflightDeploy(environment, toAgentId, version);
    for (const issue of preflight.grantIssues ?? []) {
      if (!issue.tenantId || !issue.keyName || candidates.has(`${issue.tenantId}/${issue.keyName}`)) continue;
      try {
        // Fresh, by name — the preflight's own list is only shown to admins and may be stale.
        consider(await findSecret(issue.tenantId, issue.keyName));
      } catch {
        // This one stays for the grant dialog.
      }
    }
  } catch {
    // An older backend (404) or a caller who may not edit the agent (403).
  }

  const carried: string[] = [];
  for (const [ref, { tenantId, keyName }] of candidates) {
    try {
      await grantAgentToSecret(tenantId, keyName, toAgentId);
      carried.push(ref);
    } catch {
      // Not an admin, or the grant is at its cap: the grant dialog asks instead.
    }
  }
  return carried;
}
