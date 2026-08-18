import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

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
  return api.get<AgentDescriptor>(
    `/descriptorstore/descriptors/${id}?version=${version}`
  );
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
