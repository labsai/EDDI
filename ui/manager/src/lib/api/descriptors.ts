import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";
import { mapWithConcurrency } from "../concurrency";

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
    `/descriptorstore/descriptors/${id}?version=${version}`,
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
    `/descriptorstore/descriptors/${encodeURIComponent(id)}?${params.toString()}`
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
 * A version that cannot be read (a gap, a permission change) is skipped rather
 * than failing the whole list. Bounded concurrency: an agent at v200 is 200
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
    } catch {
      return null;
    }
  });
  return results.filter((d): d is AgentDescriptor => d != null && typeof d.resource === "string");
}

/** Read descriptors for a given resource type */
export function getDescriptors(
  resourceType: string,
  limit = 100,
  index = 0,
  filter = ""
): Promise<AgentDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<AgentDescriptor[]>(
    `/${resourceType}/descriptors?${params.toString()}`
  );
}
