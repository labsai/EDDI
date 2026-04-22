import { api } from "../api-client";
import type { AgentDescriptor } from "./agents";

export { parseResourceUri } from "./agents";
export type { AgentDescriptor as ResourceDescriptor };

/** Configuration for a resource type slug → store path + plural path */
export interface ResourceTypeConfig {
  slug: string;
  store: string;
  plural: string;
  /** The backend extension type used in eddi:// URI schemes (e.g. "ai.labs.property") */
  extension: string;
  labelKey: string;
  icon: string;
}

/** All supported resource types */
export const RESOURCE_TYPES: ResourceTypeConfig[] = [
  {
    slug: "rules",
    store: "rulestore",
    plural: "rulesets",
    extension: "ai.labs.rules",
    labelKey: "resources.types.rules",
    icon: "GitBranch",
  },
  {
    slug: "apicalls",
    store: "apicallstore",
    plural: "apicalls",
    extension: "ai.labs.apicalls",
    labelKey: "resources.types.apicalls",
    icon: "Globe",
  },
  {
    slug: "output",
    store: "outputstore",
    plural: "outputsets",
    extension: "ai.labs.output",
    labelKey: "resources.types.output",
    icon: "MessageSquareText",
  },
  {
    slug: "dictionary",
    store: "dictionarystore",
    plural: "dictionaries",
    extension: "ai.labs.dictionary",
    labelKey: "resources.types.dictionary",
    icon: "BookOpen",
  },
  {
    slug: "llm",
    store: "llmstore",
    plural: "llms",
    extension: "ai.labs.llm",
    labelKey: "resources.types.llm",
    icon: "Brain",
  },
  {
    slug: "propertysetter",
    store: "propertysetterstore",
    plural: "propertysetters",
    extension: "ai.labs.property",
    labelKey: "resources.types.propertysetter",
    icon: "Settings",
  },
  {
    slug: "mcpcalls",
    store: "mcpcallsstore",
    plural: "mcpcalls",
    extension: "ai.labs.mcpcalls",
    labelKey: "resources.types.mcpcalls",
    icon: "Plug",
  },
  {
    slug: "rag",
    store: "ragstore",
    plural: "rags",
    extension: "ai.labs.rag",
    labelKey: "resources.types.rag",
    icon: "BookOpenCheck",
  },
  {
    slug: "snippets",
    store: "snippetstore",
    plural: "snippets",
    extension: "ai.labs.snippet",
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

/**
 * Get all versions of a specific resource.
 *
 * The GET descriptors endpoint does NOT support includePreviousVersions;
 * that parameter only works on POST (containingResourceUri lookup).
 * Instead, we resolve the current (latest) version via the backend's
 * `currentversion` endpoint and return descriptors for all versions 1..N.
 */
export async function getResourceVersions(
  rt: ResourceTypeConfig,
  id: string
): Promise<AgentDescriptor[]> {
  // Try to resolve the latest version number via the dedicated endpoint
  let latest: number | null = null;
  try {
    const currentVersion = await api.get<number>(
      `${basePath(rt)}/${id}/currentversion`
    );
    latest = currentVersion ?? null;
  } catch {
    // currentversion endpoint may fail (404 on unmigrated data, missing resource, etc.)
    // Fall through to descriptor-based resolution below
  }

  if (latest !== null && latest > 0) {
    // Fetch descriptor for each version in parallel
    const descriptors = await Promise.all(
      Array.from({ length: latest }, (_, i) => i + 1).map(async (v) => {
        try {
          const results = await api.get<AgentDescriptor[]>(
            `${basePath(rt)}/descriptors?filter=${id}&version=${v}`
          );
          return results;
        } catch {
          return [];
        }
      })
    );

    const flat = descriptors.flat();
    if (flat.length > 0) {
      return flat;
    }
  }

  // Fallback: query descriptors directly (unversioned) and deduce versions from URIs
  const fallback = await api.get<AgentDescriptor[]>(
    `${basePath(rt)}/descriptors?filter=${id}`
  );

  // Filter to only descriptors whose resource URI contains this exact ID
  return fallback.filter(
    (d) => d.resource?.includes(`/${id}`)
  );
}
