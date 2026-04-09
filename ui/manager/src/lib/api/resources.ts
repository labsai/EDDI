import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

export { parseResourceUri } from "./agents";
export type { AgentDescriptor as ResourceDescriptor };

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
    slug: "rules",
    store: "rulestore",
    plural: "rulesets",
    labelKey: "resources.types.rules",
    icon: "GitBranch",
  },
  {
    slug: "apicalls",
    store: "apicallstore",
    plural: "apicalls",
    labelKey: "resources.types.apicalls",
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
    slug: "dictionary",
    store: "dictionarystore",
    plural: "dictionaries",
    labelKey: "resources.types.dictionary",
    icon: "BookOpen",
  },
  {
    slug: "llm",
    store: "llmstore",
    plural: "llms",
    labelKey: "resources.types.llm",
    icon: "Brain",
  },
  {
    slug: "propertysetter",
    store: "propertysetterstore",
    plural: "propertysetters",
    labelKey: "resources.types.propertysetter",
    icon: "Settings",
  },
  {
    slug: "mcpcalls",
    store: "mcpcallsstore",
    plural: "mcpcalls",
    labelKey: "resources.types.mcpcalls",
    icon: "Plug",
  },
  {
    slug: "rag",
    store: "ragstore",
    plural: "rags",
    labelKey: "resources.types.rag",
    icon: "BookOpenCheck",
  },
  {
    slug: "snippets",
    store: "snippetstore",
    plural: "snippets",
    labelKey: "resources.types.snippets",
    icon: "Puzzle",
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
): Promise<AgentDescriptor[]> {
  const params = new URLSearchParams({
    limit: String(limit),
    index: String(index),
  });
  if (filter) params.set("filter", filter);
  return api.get<AgentDescriptor[]>(
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
  version: number,
  options?: { permanent?: boolean }
): Promise<void> {
  const params = new URLSearchParams({ version: String(version) });
  if (options?.permanent) params.set("permanent", "true");
  return api.delete(`${basePath(rt)}/${id}?${params}`);
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
): Promise<AgentDescriptor[]> {
  return api.get<AgentDescriptor[]>(
    `${basePath(rt)}/descriptors?filter=${id}&includePreviousVersions=true`
  );
}
