import { useCallback, useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import { getDescriptor } from "@/lib/api/descriptors";
import { parseResourceUri } from "@/lib/api/agents";

/** The resource a workflow step's config URI names, or null when it names none. */
export function stepResourceRef(uri: unknown): { id: string; version: number } | null {
  if (typeof uri !== "string" || !uri) return null;
  try {
    const { id, version } = parseResourceUri(uri);
    return id ? { id, version } : null;
  } catch {
    return null;
  }
}

/**
 * The descriptor NAME behind each step's config resource, keyed by resource id.
 *
 * Read one descriptor per referenced resource (same query key as the parser
 * editor's dictionary names, so they share the cache) rather than from a
 * store's paginated `…/descriptors` listing, which a resource can fall outside
 * of. A descriptor that cannot be read, or has no name, is simply absent: the
 * caller falls back to the id.
 */
export function useStepResourceNames(
  steps: ReadonlyArray<{ config?: { uri?: unknown } | null }>,
): ReadonlyMap<string, string> {
  const refs = useMemo(() => {
    const seen = new Map<string, { id: string; version: number }>();
    for (const step of steps) {
      const ref = stepResourceRef(step.config?.uri);
      if (ref) seen.set(`${ref.id}@${ref.version}`, ref);
    }
    return [...seen.values()];
  }, [steps]);

  const combine = useCallback(
    (results: ReadonlyArray<{ data?: { name?: string } }>) => {
      const names = new Map<string, string>();
      refs.forEach((ref, i) => {
        const name = results[i]?.data?.name;
        if (name) names.set(ref.id, name);
      });
      return names as ReadonlyMap<string, string>;
    },
    [refs],
  );

  return useQueries({
    queries: refs.map(({ id, version }) => ({
      queryKey: ["descriptor", id, version],
      queryFn: () => getDescriptor(id, version),
      staleTime: 5 * 60_000,
      retry: false,
    })),
    combine,
  });
}
