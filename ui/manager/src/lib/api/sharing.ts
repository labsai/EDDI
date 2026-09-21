import { api } from "../api-client";

/**
 * How much of a shared resource a subject may do something with.
 *
 * Ordered least to most; each level implies every level below it. `USE` and
 * `VIEW` are separate because letting a colleague *talk to* an agent is a
 * different act from letting them read its system prompt, tool list and vault
 * references — and the first is by far the more common request.
 */
export type AccessLevel = "USE" | "VIEW" | "EDIT" | "OWN";

export const ACCESS_LEVELS: readonly AccessLevel[] = ["USE", "VIEW", "EDIT", "OWN"];

/** Ranking used to compare two levels. Mirrors the backend's enum ordinal. */
const LEVEL_RANK: Record<AccessLevel, number> = { USE: 0, VIEW: 1, EDIT: 2, OWN: 3 };

/** Whether holding `held` satisfies a requirement for `required`. */
export function levelIncludes(held: AccessLevel | null | undefined, required: AccessLevel): boolean {
  if (!held) return false;
  return LEVEL_RANK[held] >= LEVEL_RANK[required];
}

/**
 * Who can reach a resource before any explicit share is considered.
 *
 * Deliberately *not* named after the `self / group / global` of persistent user
 * memory — that vocabulary refers to *agent* groups and means something else
 * entirely.
 */
export type ResourceVisibility = "private" | "space" | "published";

export const RESOURCE_VISIBILITIES: readonly ResourceVisibility[] = [
  "private",
  "space",
  "published",
];

/** One explicit share of one resource with one subject. */
export interface ResourceGrant {
  /** `user:<principal>` or `team:<group path>`. */
  subject: string;
  level: AccessLevel;
  grantedBy?: string;
  grantedOn?: number;
}

/** How a resource is shared, plus what the calling user may do with it. */
export interface ShareInfo {
  resourceId: string;
  /**
   * The recorded owner, or absent for data that predates ownership.
   *
   * Optional, not `| null` alone: EDDI's REST mapper serialises with
   * `NON_NULL`, so an unowned resource omits this field entirely.
   */
  ownerId?: string | null;
  /** `user:<principal>` or `team:<group>` or `legacy`, absent when unrecorded. */
  spaceId?: string | null;
  visibility: ResourceVisibility;
  /**
   * Explicit shares. **Empty unless the caller owns the resource** — the
   * backend discloses the grant list at `OWN` only, because a published
   * resource is readable by everyone and its grant audience is a list of real
   * principal and team names.
   */
  grants: ResourceGrant[];
  /**
   * What this user may do with the resource.
   *
   * **Not the same field as `AgentDescriptor.callerLevel`, despite the name.**
   * This one comes from the sharing endpoint, which refuses the whole request
   * below `VIEW` — so in practice it is always present and always at least
   * `VIEW`. The descriptor field is stamped on every listed row instead, and is
   * *absent* when the deployment does not enforce workspaces, which
   * `accessFor()` reads as unrestricted.
   *
   * Reading absence here the way `accessFor` reads it there would be wrong in
   * both directions, so neither should be substituted for the other.
   */
  callerLevel?: AccessLevel | null;
}

/** One resource a share touched — or declined to. */
export interface ShareTarget {
  id: string;
  /** Its descriptor name, absent when it has none. */
  name?: string | null;
}

/**
 * The outcome of a share, revoke, publish or transfer.
 *
 * `skipped` is not an error: it lists resources reachable from the one you
 * shared that you do not own, and therefore cannot pass on. Showing it is the
 * difference between "shared" and "shared, except these three things which
 * belong to someone else".
 */
export interface ShareResult {
  updated: ShareTarget[];
  skipped: ShareTarget[];
}

const basePath = (resourceId: string) =>
  `/descriptorstore/descriptors/${encodeURIComponent(resourceId)}/shares`;

/** Read how a resource is shared. Requires read access to it. */
export function getShareInfo(resourceId: string): Promise<ShareInfo> {
  return api.get<ShareInfo>(basePath(resourceId));
}

/**
 * Grant a person or team access.
 *
 * `cascade` defaults to true for a reason: an agent is a thin document pointing
 * at workflows, which point at rule sets, LLM configs and output sets. Sharing
 * only the agent hands the recipient a name and a list of URIs they cannot
 * open.
 */
export function shareResource(
  resourceId: string,
  subject: string,
  level: AccessLevel,
  cascade = true
): Promise<ShareResult> {
  const params = new URLSearchParams({ subject, level, cascade: String(cascade) });
  return api.post<ShareResult>(`${basePath(resourceId)}?${params.toString()}`, undefined);
}

/** Remove a subject's grant, mirroring {@link shareResource}. */
export function revokeShare(
  resourceId: string,
  subject: string,
  cascade = true
): Promise<ShareResult> {
  const params = new URLSearchParams({ subject, cascade: String(cascade) });
  return api.delete<ShareResult>(`${basePath(resourceId)}?${params.toString()}`);
}

/** Set visibility: private, space or published. */
export function setResourceVisibility(
  resourceId: string,
  visibility: ResourceVisibility,
  cascade = true
): Promise<ShareResult> {
  const params = new URLSearchParams({ visibility, cascade: String(cascade) });
  return api.put<ShareResult>(
    `${basePath(resourceId)}/visibility?${params.toString()}`,
    undefined
  );
}

/**
 * Reassign ownership. Administrators only — this exists to recover resources
 * whose owner has left, which cannot depend on that owner acting.
 */
export function transferOwnership(
  resourceId: string,
  ownerId: string,
  spaceId?: string,
  cascade = true
): Promise<ShareResult> {
  const params = new URLSearchParams({ ownerId, cascade: String(cascade) });
  if (spaceId) params.set("spaceId", spaceId);
  return api.put<ShareResult>(`${basePath(resourceId)}/owner?${params.toString()}`, undefined);
}
