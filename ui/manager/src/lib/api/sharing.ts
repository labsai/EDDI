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
export type ResourceVisibility = "private" | "space" | "internal" | "published";

/**
 * The visibilities, narrowest first. `internal` sits between `space` and
 * `published`: everyone signed in may *use* it, nobody reads its configuration
 * through visibility alone, and anonymous callers are not admitted at all.
 */
export const RESOURCE_VISIBILITIES: readonly ResourceVisibility[] = [
  "private",
  "space",
  "internal",
  "published",
];

/** One explicit share of one resource with one subject. */
export interface ResourceGrant {
  /** `user:<principal>` or `team:<group path>`. */
  subject: string;
  level: AccessLevel;
  grantedBy?: string;
  grantedOn?: number;
  /** `user` or `team`. Absent from a backend that predates the user directory. */
  kind?: "user" | "team";
  /** A person's name or a team's name, for display. Never send it back. */
  label?: string;
  /** A secondary line — the person's email, when the deployment shows emails. */
  detail?: string | null;
  /**
   * Whether the subject still names somebody who has signed in. A grant made
   * before the directory existed can name a principal that never signs in —
   * such as one typed as an email address — and reaches nobody.
   */
  known?: boolean;
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
  /** The owner's name from the user directory, else their principal. */
  ownerLabel?: string | null;
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
  /** True for a preview: the lists say what WOULD change, and nothing did. */
  dryRun?: boolean;
}

/** Options every sharing change takes. */
export interface ShareOptions {
  /** Apply to everything the resource references that the caller owns. Default true. */
  cascade?: boolean;
  /** Report what would change without writing anything. Default false. */
  dryRun?: boolean;
}

function changeParams(base: Record<string, string>, options: ShareOptions = {}): string {
  const params = new URLSearchParams({ ...base, cascade: String(options.cascade ?? true) });
  if (options.dryRun) params.set("dryRun", "true");
  return params.toString();
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
  options: ShareOptions = {}
): Promise<ShareResult> {
  return api.post<ShareResult>(`${basePath(resourceId)}?${changeParams({ subject, level }, options)}`, undefined);
}

/** Remove a subject's grant, mirroring {@link shareResource}. */
export function revokeShare(
  resourceId: string,
  subject: string,
  options: ShareOptions = {}
): Promise<ShareResult> {
  return api.delete<ShareResult>(`${basePath(resourceId)}?${changeParams({ subject }, options)}`);
}

/** Set visibility: private, space, internal or published. */
export function setResourceVisibility(
  resourceId: string,
  visibility: ResourceVisibility,
  options: ShareOptions = {}
): Promise<ShareResult> {
  return api.put<ShareResult>(`${basePath(resourceId)}/visibility?${changeParams({ visibility }, options)}`, undefined);
}

/**
 * File a resource the caller owns under another of their spaces — how personal
 * work becomes team work. Ownership does not change; the team gains edit access
 * through the space.
 */
export function moveToSpace(resourceId: string, spaceId: string, options: ShareOptions = {}): Promise<ShareResult> {
  return api.put<ShareResult>(`${basePath(resourceId)}/space?${changeParams({ spaceId }, options)}`, undefined);
}

/** What the server says happened to an access request. */
export type AccessRequestOutcome = "SENT" | "ALREADY_HAS_ACCESS" | "ALREADY_REQUESTED";

/**
 * Ask the owner of a resource the caller cannot open for access.
 *
 * The answer never says whether the resource exists or who owns it — an id
 * that matches nothing is answered `SENT` too — so do not phrase the
 * confirmation as "the owner was notified".
 */
export async function requestAccess(
  resourceId: string,
  level: Exclude<AccessLevel, "OWN">,
  message?: string
): Promise<AccessRequestOutcome> {
  const params = new URLSearchParams({ level });
  if (message?.trim()) params.set("message", message.trim());
  const result = await api.post<{ outcome: AccessRequestOutcome } | undefined>(
    `${basePath(resourceId)}/requests?${params.toString()}`,
    undefined
  );
  // An older server answered 202 with no body; SENT was its only answer.
  return result?.outcome ?? "SENT";
}

/**
 * Reassign ownership. Administrators only — this exists to recover resources
 * whose owner has left, which cannot depend on that owner acting.
 */
export function transferOwnership(
  resourceId: string,
  ownerId: string,
  spaceId?: string,
  options: ShareOptions = {}
): Promise<ShareResult> {
  const base: Record<string, string> = { ownerId };
  if (spaceId) base.spaceId = spaceId;
  return api.put<ShareResult>(`${basePath(resourceId)}/owner?${changeParams(base, options)}`, undefined);
}
