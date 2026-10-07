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
import { updateDescriptor } from "@/lib/api/descriptors";
import {
  cascadePartialResult,
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
  filter = "",
  /** Set false to hold the request back — e.g. a picker that is mounted but
   *  not open yet, which would otherwise list a store nobody asked to see. */
  enabled = true
) {
  const rt = resolveType(slug);
  return useQuery({
    queryKey: [...resourceKeys(slug), "descriptors", { limit, index, filter }],
    queryFn: () => getResourceDescriptors(rt!, limit, index, filter),
    enabled: !!rt && enabled,
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
    // Moving onto another version of the SAME resource (after a save, or via the
    // version picker) keeps the current editor mounted — and with it the active
    // tab, scroll position and form state — instead of flashing a skeleton.
    // A different resource never borrows the previous one's data.
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === id ? previous : undefined,
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
    mutationFn: async ({
      body = {},
      name,
      description,
    }: {
      body?: unknown;
      name?: string;
      description?: string;
    }) => {
      if (!rt) throw new Error(`Unknown resource type: ${slug}`);
      const response = await createResource(rt, body);
      if ((name || description) && response.location) {
        const url = new URL(response.location, "http://dummy");
        const parts = url.pathname.split("/").filter(Boolean);
        const id = parts[parts.length - 1]!;
        const version = parseInt(url.searchParams.get("version") || "1", 10);
        await updateDescriptor(id, version, { name, description });
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
    },
  });
}

/** Rename a resource — its name and description live on the descriptor. */
export function useUpdateResourceDescriptor(slug: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      name,
      description,
    }: {
      id: string;
      version: number;
      name: string;
      description: string;
    }) => updateDescriptor(id, version, { name, description }),
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
      skipResourceSave,
      compatible,
    }: {
      id: string;
      version: number;
      body: unknown;
      context?: CascadeContext;
      skipResourceSave?: boolean;
      /** The agent version the cascade writes is compatible — see `CascadeOptions`. */
      compatible?: boolean;
    }) => {
      if (!rt)
        return Promise.reject(new Error(`Unknown resource type: ${slug}`));
      return cascadeSaveResource(rt, id, version, body, context, {
        skipResourceSave,
        ...(compatible ? { compatible: true } : {}),
      });
    },
    onSuccess: (result, { id, body, skipResourceSave }) => {
      // Seed the version the save created with the body just written, so an
      // editor that moves onto it keeps rendering instead of dropping to a
      // loading state (and losing whatever was typed meanwhile).
      if (!skipResourceSave) {
        queryClient.setQueryData([...resourceKeys(slug), id, result.newResourceVersion], body);
      }
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
      queryClient.invalidateQueries({ queryKey: ["workflows"] });
      queryClient.invalidateQueries({ queryKey: ["agents"] });
    },
    onError: (err, { id, body }) => {
      // A cascade that failed partway still wrote new versions, and the page
      // moves onto them — the version lists must show them too.
      const partial = cascadePartialResult(err);
      if (!partial) return;
      if (partial.newResourceVersion !== undefined) {
        queryClient.setQueryData([...resourceKeys(slug), id, partial.newResourceVersion], body);
      }
      queryClient.invalidateQueries({ queryKey: resourceKeys(slug) });
      if (partial.newWorkflowVersion !== undefined) {
        queryClient.invalidateQueries({ queryKey: ["workflows"] });
      }
    },
  });
}
