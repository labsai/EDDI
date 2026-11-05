import { api, isApiError } from "../api-client";
import { mapWithConcurrency } from "../concurrency";
import { seg } from "./path-segment";

// Types matching EDDI backend
export interface AgentDescriptor {
  resource: string;
  name: string;
  description: string;
  createdOn: number;
  lastModifiedOn: number;
  createdBy?: string;
  lastModifiedBy?: string;
  /**
   * Workspace fields. Absent on a backend without workspaces, and on data that
   * predates ownership being recorded — so every consumer must treat them as
   * optional rather than assuming a listing carries them.
   *
   * `grants` and `accessIndex` are deliberately NOT here: the backend redacts
   * them for anyone who does not own the resource, so a listing is not a place
   * to read them. Use the share endpoint, which discloses them at OWN only.
   */
  ownerId?: string;
  spaceId?: string;
  visibility?: "private" | "space" | "internal" | "published";
  /**
   * The owner's name from the user directory, for showing a person to a person.
   * Absent when the directory does not know them or workspaces are not enforced
   * — fall back to `ownerId`.
   */
  ownerName?: string;
  /**
   * What the signed-in user may do with THIS resource — `USE`, `VIEW`, `EDIT`
   * or `OWN`.
   *
   * Per-request rather than per-resource: the same document carries a different
   * value for two callers. Absent when the backend does not enforce workspaces,
   * and on any backend that predates the field — read it through
   * `accessFor()` in `@/lib/access`, which treats absence as unrestricted.
   */
  callerLevel?: string;
  /**
   * The agent's id on the instance it was imported or synced from. It is what
   * recognises the local copy of a remote agent — a name can be changed on
   * either side, or shared by two agents.
   */
  originId?: string;
}

export interface PatchInstruction<T> {
  operation: "SET" | "DELETE";
  document: Partial<T>;
}

/** Update a document descriptor (name + description) */
export function updateDescriptor(
  id: string,
  version: number,
  descriptor: { name?: string; description?: string; resources?: Record<string, unknown> }
): Promise<void> {
  const patch: PatchInstruction<typeof descriptor> = {
    operation: "SET",
    document: descriptor,
  };
  return api.patch(
    `/descriptorstore/descriptors/${seg(id)}?version=${version}`,
    patch
  );
}

/**
 * Read one document descriptor by id and version.
 *
 * The per-store `…/descriptors` listing is the usual way to get a name, but it
 * is a paginated view a resource can legitimately fall outside of — past the
 * first page, or (as some deployments show) missing from a store listing
 * entirely while the descriptor itself resolves fine. Reading the one
 * descriptor a reference actually points at has neither problem.
 */
export function getDescriptor(
  id: string,
  version: number
): Promise<AgentDescriptor> {
  // Encoded: this id can come from a URI someone typed by hand, and a `?`,
  // `#` or `..` in it would otherwise re-point the request at another path.
  const params = new URLSearchParams({ version: String(version) });
  return api.get<AgentDescriptor>(
    `/descriptorstore/descriptors/${seg(id)}?${params.toString()}`
  );
}

/** How many per-version descriptor reads a version list keeps in flight. */
const VERSION_READ_CONCURRENCY = 6;

/**
 * The descriptor of every version `1..latest` of one resource, oldest first,
 * for version pickers, Compare and rollback.
 *
 * Each version is read by id AND version through the descriptor store, which
 * resolves older versions from history. The per-store `…/descriptors` listing
 * cannot do this: it has no `version` parameter and lists only the current
 * collection, so asking it "filter=id&version=v" for v = 1..N returned the
 * latest descriptor N times — every picker offered only the newest version (under
 * N duplicate keys) and Compare diffed vN against vN.
 *
 * Only a 404 is skipped — a genuine gap in the history. Anything else (an
 * expired session, a 403, a 5xx) fails the whole list: silently dropping those
 * versions would bring back exactly the "only some versions" picker this fixes,
 * with no error to explain it. Bounded concurrency: an agent at v200 is 200
 * reads, and an unbounded fan-out would stall the browser's connection pool.
 */
export async function getDescriptorVersions(
  id: string,
  latest: number,
): Promise<AgentDescriptor[]> {
  const versions = Array.from({ length: Math.max(0, latest) }, (_, i) => i + 1);
  const results = await mapWithConcurrency(versions, VERSION_READ_CONCURRENCY, async (v) => {
    try {
      return await getDescriptor(id, v);
    } catch (err) {
      if (isApiError(err) && err.status === 404) return null;
      throw err;
    }
  });
  return results.filter((d): d is AgentDescriptor => d != null && typeof d.resource === "string");
}
