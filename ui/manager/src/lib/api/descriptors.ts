import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

/** Update a document descriptor (name + description) */
export function updateDescriptor(
  resourceType: string,
  id: string,
  version: number,
  descriptor: { name: string; description: string }
): Promise<void> {
  return api.patch(
    `/${resourceType}/descriptors/${id}?version=${version}`,
    descriptor
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
