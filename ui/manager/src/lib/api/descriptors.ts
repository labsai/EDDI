import { api } from "../api-client";
import type { BotDescriptor } from "./bots";

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
): Promise<BotDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<BotDescriptor[]>(
    `/${resourceType}/descriptors?${params.toString()}`
  );
}
