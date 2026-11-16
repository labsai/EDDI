import {
  AUTH_TYPES,
  emptyConnection,
  type AuthType,
  type ConnectionConfiguration,
  type OAuthConfig,
  type StaticAuth,
} from "./api/connections";

/**
 * Starting points for a new connection, kept as data.
 *
 * A connection describes an auth *shape*, never a provider — the backend's
 * `AuthType` is deliberately closed and names no vendor. What differs between
 * Google and Entra is a token URL, a scope and which fields carry a value, so a
 * preset is a JSON file under `connection-presets/` and adding one is adding a
 * file. Nothing here is trusted: a preset only pre-fills the wizard, and the
 * document it produces passes the same client- and server-side validation as
 * one typed by hand — including the operator's credential-endpoint allowlist,
 * which no preset can widen.
 */
export interface ConnectionPreset {
  id: string;
  label: string;
  description: string;
  connection: {
    authType: AuthType;
    binding?: ConnectionConfiguration["binding"];
    oauth?: OAuthConfig;
    staticAuth?: StaticAuth;
    baseUrlAllowlist?: string[];
  };
}

const modules = import.meta.glob<{ default: ConnectionPreset }>("./connection-presets/*.json", {
  eager: true,
});

/** Every preset whose auth type this Manager knows, sorted by label. */
export const CONNECTION_PRESETS: readonly ConnectionPreset[] = Object.values(modules)
  .map((module) => module.default)
  .filter((preset) => (AUTH_TYPES as readonly string[]).includes(preset.connection.authType))
  .sort((a, b) => a.label.localeCompare(b.label));

/**
 * A draft built from a preset: the blank connection of its type with the
 * preset's fields laid over it. The name and description the author already
 * typed are kept.
 */
export function applyPreset(
  preset: ConnectionPreset,
  current: Pick<ConnectionConfiguration, "name" | "description">,
): ConnectionConfiguration {
  const blank = emptyConnection(preset.connection.authType, preset.connection.binding);
  return {
    ...blank,
    name: current.name,
    description: current.description,
    baseUrlAllowlist: [...(preset.connection.baseUrlAllowlist ?? [])],
    staticAuth: preset.connection.staticAuth
      ? { ...blank.staticAuth, ...preset.connection.staticAuth }
      : blank.staticAuth,
    oauth: preset.connection.oauth ? { ...blank.oauth, ...preset.connection.oauth } : blank.oauth,
  };
}
