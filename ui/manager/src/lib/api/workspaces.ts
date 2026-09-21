import { api, ApiClientError } from "../api-client";

/** One space the caller can reach, as the server describes it. */
export interface SpaceInfo {
  /**
   * The wire id to send back as `?space=`. **Opaque** — it carries escaping
   * that must not be re-derived here.
   */
  id: string;
  kind: "personal" | "team";
  /** The decoded name to show a human. Never the raw id. */
  label: string;
}

/** What workspaces mean for the calling user on this deployment. */
export interface WorkspaceInfo {
  /**
   * Whether the backend actually enforces workspaces.
   *
   * This is the field the UI gates on. It cannot be inferred from the data: a
   * deployment with workspaces off returns descriptors that look exactly like
   * one where everything predates ownership.
   */
  enabled: boolean;
  /**
   * The caller's principal name, or absent when anonymous.
   *
   * This — not the token's display name — is what the backend stamps as
   * `ownerId`, so it is what "is this mine?" must compare against.
   *
   * Optional because EDDI's REST mapper serialises with `NON_NULL`: a null
   * principal is *missing from the payload*, not `null` in it. Typing it as
   * `string | null` would make a future `=== null` check silently never match.
   */
  principal?: string | null;
  /** The space new resources land in, or absent when anonymous. */
  defaultSpace?: string | null;
  /** Every space the caller can reach, personal first. */
  spaces: SpaceInfo[];
  /** Whether this caller's reach is unlimited (admin, or enforcement off). */
  seesEverything: boolean;
}

/**
 * A deployment that has never heard of workspaces.
 *
 * Returned when the endpoint 404s, which is exactly what an EDDI older than
 * this feature does. Everything degrades to today's behaviour: no switcher, no
 * sharing, no badges.
 */
export const WORKSPACES_UNAVAILABLE: WorkspaceInfo = {
  enabled: false,
  principal: null,
  defaultSpace: null,
  spaces: [],
  seesEverything: true,
};

/** Normalises the absent-vs-null difference away for consumers. */
export function principalOf(info: WorkspaceInfo): string | null {
  return info.principal ?? null;
}

/**
 * Reads the caller's workspace context.
 *
 * Resolves to {@link WORKSPACES_UNAVAILABLE} on 404 rather than rejecting: an
 * older backend is a supported deployment, not an error worth showing anybody.
 * Every other failure — 401, 403, a network fault — is rethrown, because those
 * mean "we could not find out", and quietly answering "workspaces are off"
 * would hide a real problem behind a plausible-looking UI.
 */
export async function getWorkspaceInfo(): Promise<WorkspaceInfo> {
  try {
    return await api.get<WorkspaceInfo>("/workspaces");
  } catch (e) {
    if (e instanceof ApiClientError && e.status === 404) {
      return WORKSPACES_UNAVAILABLE;
    }
    throw e;
  }
}
