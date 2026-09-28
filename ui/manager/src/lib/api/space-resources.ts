import { api } from "../api-client";

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
  createdAt?: string | null;
}

export interface SpaceVariable {
  key: string;
  value: string;
  description?: string | null;
  reference: string;
}

const q = (space: string) => `space=${encodeURIComponent(space)}`;
const seg = (key: string) => encodeURIComponent(key);

export function listSpaceSecrets(space: string): Promise<SpaceSecret[]> {
  return api.get<SpaceSecret[]>(`/spacestore/secrets?${q(space)}`);
}

/** The value is write-only: it is encrypted and never returned. */
export function storeSpaceSecret(
  space: string,
  keyName: string,
  value: string,
  description?: string
): Promise<SpaceSecret> {
  return api.put<SpaceSecret>(`/spacestore/secrets/${seg(keyName)}?${q(space)}`, { value, description });
}

export function deleteSpaceSecret(space: string, keyName: string): Promise<void> {
  return api.delete<void>(`/spacestore/secrets/${seg(keyName)}?${q(space)}`);
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
  return api.put<SpaceVariable>(`/spacestore/variables/${seg(key)}?${q(space)}`, { value, description });
}

export function deleteSpaceVariable(space: string, key: string): Promise<void> {
  return api.delete<void>(`/spacestore/variables/${seg(key)}?${q(space)}`);
}
