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
