/**
 * Helpers for the vault references `SecretKeyPicker` produces.
 *
 * The picker's value is either a vault reference (`vault:NAME`, `${vault:NAME}`,
 * or the legacy `eddivault:` spelling) or a plain-text secret the user pasted.
 * The operator config stores only the key *name*, never a secret, so plain text
 * must resolve to `null` rather than being persisted as if it were a name.
 */

const VAULT_REF = /^\$?\{?(?:eddi)?vault:([^}]+)\}?$/;

/**
 * Format a vault key name as the canonical reference.
 *
 * The braces matter: the backend's `SecretReference.isVaultReference` requires
 * `${vault:...}`, and a bare `vault:KEY` falls through to the plaintext branch
 * and is stored verbatim as the provider credential.
 */
export function toVaultRef(keyName: string): string {
  return `\${vault:${keyName}}`;
}

/** Extract the vault key name, or `null` when the value is not a vault reference. */
export function extractVaultKeyName(value: string): string | null {
  const match = VAULT_REF.exec(value.trim());
  return match ? match[1]!.trim() : null;
}
