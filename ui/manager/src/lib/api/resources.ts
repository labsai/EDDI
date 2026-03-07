import { api } from "../api-client";
import type { BotDescriptor } from "./bots";

export { parseResourceUri } from "./bots";
export type { BotDescriptor as ResourceDescriptor };

/** Configuration for a resource type slug → store path + plural path */
export interface ResourceTypeConfig {
  slug: string;
  store: string;
  plural: string;
  labelKey: string;
  icon: string;
}

/** All supported resource types */
export const RESOURCE_TYPES: ResourceTypeConfig[] = [
  {
    slug: "behavior",
    store: "behaviorstore",
    plural: "behaviorsets",
    labelKey: "resources.types.behavior",
    icon: "GitBranch",
  },
  {
    slug: "httpcalls",
    store: "httpcallsstore",
    plural: "httpcalls",
    labelKey: "resources.types.httpcalls",
    icon: "Globe",
  },
  {
    slug: "output",
    store: "outputstore",
    plural: "outputsets",
    labelKey: "resources.types.output",
    icon: "MessageSquareText",
  },
  {
    slug: "dictionaries",
    store: "regulardictionarystore",
    plural: "regulardictionaries",
    labelKey: "resources.types.dictionaries",
    icon: "BookOpen",
  },
  {
    slug: "langchain",
    store: "langchainstore",
    plural: "langchains",
    labelKey: "resources.types.langchain",
    icon: "Brain",
  },
  {
    slug: "propertysetter",
    store: "propertysetterstore",
    plural: "propertysetters",
    labelKey: "resources.types.propertysetter",
    icon: "Settings",
  },
];

/** Look up a resource type config by slug */
export function getResourceType(slug: string): ResourceTypeConfig | undefined {
  return RESOURCE_TYPES.find((rt) => rt.slug === slug);
}

/** Build the base path for a resource type */
function basePath(rt: ResourceTypeConfig): string {
  return `/${rt.store}/${rt.plural}`;
}

// --- Generic CRUD API functions ---

export function getResourceDescriptors(
  rt: ResourceTypeConfig,
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
    `${basePath(rt)}/descriptors?${params.toString()}`
  );
}

export function getResource<T = unknown>(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): Promise<T> {
  return api.get<T>(`${basePath(rt)}/${id}?version=${version}`);
}

export function createResource(
  rt: ResourceTypeConfig,
  body: unknown = {}
): Promise<{ location: string }> {
  return api.post<{ location: string }>(basePath(rt), body);
}

export function updateResource(
  rt: ResourceTypeConfig,
  id: string,
  version: number,
  body: unknown
): Promise<{ location: string }> {
  return api.put(`${basePath(rt)}/${id}?version=${version}`, body);
}

export function deleteResource(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): Promise<void> {
  return api.delete(`${basePath(rt)}/${id}?version=${version}`);
}

export function duplicateResource(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `${basePath(rt)}/${id}?version=${version}`
  );
}

/** Get all versions of a specific resource */
export function getResourceVersions(
  rt: ResourceTypeConfig,
  id: string
): Promise<BotDescriptor[]> {
  return api.get<BotDescriptor[]>(
    `${basePath(rt)}/descriptors?filter=${id}&includePreviousVersions=true`
  );
}
