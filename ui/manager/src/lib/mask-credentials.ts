/**
 * Masking of credential-shaped values for screens that print stored documents
 * verbatim (the version diff).
 *
 * A config can hold a literal key typed before the vault existed, or one a
 * connector wrote in plaintext; printing it in a diff puts it on screen (and in
 * a screenshot) for anyone who can open the page. Values of keys whose NAME says
 * they are credentials are replaced by a mask plus a short fingerprint, so a
 * change between the two sides still shows as a difference without revealing
 * either value. References (`${vault:key}`, `${vars:name}`, `${connection:name}`)
 * are not secrets and stay readable — they are the thing worth comparing.
 */

/** Key names that say "this value is a credential" (case-insensitive). */
const CREDENTIAL_KEY =
  /(api[-_]?key|secret|passw(or)?d|passphrase|private[-_]?key|credential|authorization|(^|[-_.])token$|[a-z]token$|auth[-_]?header)/i;

const REFERENCE = /^\s*\$\{[a-zA-Z]+:/;

/** A 4-hex-digit FNV-1a fingerprint — enough to tell two values apart, not to recover one. */
function fingerprint(value: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0").slice(0, 4);
}

/** Whether `key` names a credential. */
export function isCredentialKey(key: string): boolean {
  return CREDENTIAL_KEY.test(key);
}

/** The mask shown in place of a credential value. */
export function maskCredentialValue(value: string): string {
  return `\u2022\u2022\u2022\u2022\u2022\u2022 [${fingerprint(value)}]`;
}

/**
 * A copy of a parsed JSON value with every string under a credential-shaped key
 * masked (non-empty literals only — references and empty strings stay as they are).
 */
export function maskCredentials(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(maskCredentials);
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, v] of Object.entries(value as Record<string, unknown>)) {
      if (typeof v === "string" && v.trim() !== "" && isCredentialKey(key) && !REFERENCE.test(v)) {
        out[key] = maskCredentialValue(v);
      } else {
        out[key] = maskCredentials(v);
      }
    }
    return out;
  }
  return value;
}
