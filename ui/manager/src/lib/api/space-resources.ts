import { api } from "../api-client";
import { ALL_AGENTS } from "./secrets";
import { nameSeg } from "./path-segment";

/**
 * Secrets and variables that belong to a space — a person's own, or a team's.
 *
 * Members of the space manage them; agents reach them with an explicit
 * reference (`${vault:<tenant>/key}`, `${vars:<tenant>/key}`), and every
 * response carries the reference to paste, so nobody has to derive a tenant id.
 */

export interface SpaceSecret {
  keyName: string;
  /** What to paste into a configuration. */
  reference: string;
  description?: string | null;
  allowedAgents?: string[] | null;
  /** Where a reference to it may be sent — exact `host` / `host:port`. Empty or absent: anywhere. */
  allowedHosts?: string[] | null;
  createdAt?: string | null;
}

/**
 * The body of `PUT /spacestore/secrets/{keyName}`. Under
 * `eddi.vault.grant-enforcement=enforce` (the default) a create must carry both
 * lists: `allowedAgents` (`["*"]` = every agent) and `allowedHosts` (hosts, or
 * `["*"]` for no destination restriction).
 */
export interface SpaceSecretWrite {
  value: string;
  description?: string;
  /** Required on a create; omitted on a rotation, which keeps the stored grant. */
  allowedAgents?: string[];
  /** Required on a create; omitted on a rotation, which keeps the stored list. */
  allowedHosts?: string[];
}

export interface SpaceVariable {
  key: string;
  value: string;
  description?: string | null;
  reference: string;
}

const q = (space: string) => `space=${encodeURIComponent(space)}`;

export function listSpaceSecrets(space: string): Promise<SpaceSecret[]> {
  return api.get<SpaceSecret[]>(`/spacestore/secrets?${q(space)}`);
}

/**
 * The value is write-only: it is encrypted and never returned.
 *
 * Space secrets have no per-agent grant in the Manager, so a create sends the
 * explicit wildcard — the backend refuses a create that leaves it to a default.
 * A rotation (`existing`) sends no grant at all, so it keeps whatever is stored
 * and can never widen a grant narrowed through the API. `allowedHosts` is the
 * caller's: hosts, or `["*"]` — or `undefined` on a rotation, which keeps the
 * stored list.
 */
export function storeSpaceSecret(
  space: string,
  keyName: string,
  value: string,
  allowedHosts: string[] | undefined,
  description?: string,
  existing = false
): Promise<SpaceSecret> {
  const body: SpaceSecretWrite = { value, description };
  if (allowedHosts) body.allowedHosts = allowedHosts;
  if (!existing) body.allowedAgents = [ALL_AGENTS];
  return api.put<SpaceSecret>(`/spacestore/secrets/${nameSeg(keyName)}?${q(space)}`, body);
}

export function deleteSpaceSecret(space: string, keyName: string): Promise<void> {
  return api.delete<void>(`/spacestore/secrets/${nameSeg(keyName)}?${q(space)}`);
}

export function listSpaceVariables(space: string): Promise<SpaceVariable[]> {
  return api.get<SpaceVariable[]>(`/spacestore/variables?${q(space)}`);
}

export function storeSpaceVariable(
  space: string,
  key: string,
  value: string,
  description?: string
): Promise<SpaceVariable> {
  return api.put<SpaceVariable>(`/spacestore/variables/${nameSeg(key)}?${q(space)}`, { value, description });
}

export function deleteSpaceVariable(space: string, key: string): Promise<void> {
  return api.delete<void>(`/spacestore/variables/${nameSeg(key)}?${q(space)}`);
}
