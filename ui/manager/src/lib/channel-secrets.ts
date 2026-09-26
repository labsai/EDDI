import { isSecretReference, isVaultScheme } from "@/lib/secret-reference";

/**
 * The channel platform-config keys that hold credentials.
 *
 * The backend stores whatever it is given here and returns it verbatim from
 * `GET /channelstore/channels/{id}` — to anyone who may read the channel — and
 * into every export; it only logs a warning for a plaintext value. The channel
 * router resolves `${vault:…}` references at send time, so a reference loses
 * nothing, and the Manager refuses anything else.
 */
export const CHANNEL_SECRET_KEYS = ["botToken", "signingSecret"] as const;

/**
 * Empty, or a braced vault reference. `${vars:…}` does not qualify: the router
 * resolves vault references only, so a variable reference would reach Slack as
 * its own literal text.
 */
export function isEmptyOrReference(value: string | null | undefined): boolean {
  if (!value || !value.trim()) return true;
  return isSecretReference(value) && isVaultScheme(value);
}

/** The credential keys in `platformConfig` holding something other than a vault reference. */
export function plaintextSecretFields(platformConfig: Record<string, string> | null | undefined): string[] {
  return CHANNEL_SECRET_KEYS.filter((key) => !isEmptyOrReference(platformConfig?.[key]));
}
