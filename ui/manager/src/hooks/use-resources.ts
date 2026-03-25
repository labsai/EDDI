import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getResourceDescriptors,
  getResource,
  getResourceVersions,
  createResource,
  updateResource,
  deleteResource,
  duplicateResource,
  getResourceType,
  type ResourceTypeConfig,
} from "@/lib/api/resources";
import {
  cascadeSaveResource,
  type CascadeContext,
} from "@/lib/api/cascade-save";


function resourceKeys(slug: string) {
  return ["resources", slug] as const;
}

function resolveType(slug: string): ResourceTypeConfig | undefined {
  return getResourceType(slug);
}

export function useResourceDescriptors(
  slug: string,
  limit = 100,
  index = 0,
  filter = ""
) {
  const rt = resolveType(slug);
  return useQuery({
    queryKey: [...resourceKeys(slug), "descriptors", { limit, index, filter }],
    queryFn: () => getResourceDescriptors(rt!, limit, index, filter),
    enabled: !!rt,
  });
}

export function useResource<T = unknown>(
  slug: string,
  id: string,
  version: number
) {
  const rt = resolveType(slug);
  return useQuery({
    queryKey: [...resourceKeys(slug), id, version],
    queryFn: () => getResource<T>(rt!, id, version),
    enabled: !!rt && !!id && version > 0,
  });
}

/** Fetch all versions of a specific resource (for version picker) */
export function useResourceVersions(slug: string, id: string) {
  const rt = resolveType(slug);
  return useQuery({
    queryKey: [...resourceKeys(slug), id, "versions"],
    queryFn: () => getResourceVersions(rt!, id),
    enabled: !!rt && !!id,
  });
}

export function useCreateResource(slug: string) {
  const queryClient = useQueryClient();
  const rt = resolveType(slug);
  return useMutation({
    mutationFn: (body: unknown = {}) => {
      if (!rt) return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return createResource(rt, body);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
    },
  });
}

export function useDeleteResource(slug: string) {
  const queryClient = useQueryClient();
  const rt = resolveType(slug);
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) => {
      if (!rt) return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return deleteResource(rt, id, version);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
    },
  });
}

export function useDuplicateResource(slug: string) {
  const queryClient = useQueryClient();
  const rt = resolveType(slug);
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) => {
      if (!rt) return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return duplicateResource(rt, id, version);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
    },
  });
}

export function useUpdateResource(slug: string) {
  const queryClient = useQueryClient();
  const rt = resolveType(slug);
  return useMutation({
    mutationFn: ({
      id,
      version,
      body,
    }: {
      id: string;
      version: number;
      body: unknown;
    }) => {
      if (!rt)
        return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return updateResource(rt, id, version, body);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
    },
  });
}

/**
 * Cascade save: saves a resource, then optionally updates parent
 * package and agent to reference the new version.
 */
export function useCascadeSave(slug: string) {
  const queryClient = useQueryClient();
  const rt = resolveType(slug);
  return useMutation({
    mutationFn: ({
      id,
      version,
      body,
      context,
    }: {
      id: string;
      version: number;
      body: unknown;
      context?: CascadeContext;
    }) => {
      if (!rt)
        return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return cascadeSaveResource(rt, id, version, body, context);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
      queryClient.invalidateQueries({ queryKey: ["workflows"] });
      queryClient.invalidateQueries({ queryKey: ["agents"] });
    },
  });
}
