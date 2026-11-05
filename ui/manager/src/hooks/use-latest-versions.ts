import { useQuery } from "@tanstack/react-query";
import { api } from "@eddi/app-kit/lib/api-client";
import type { AgentDescriptor } from "@eddi/app-kit/lib/api/agents";
import { parseResourceUri } from "@eddi/app-kit/lib/api/agents";

/**
 * Given a list of eddi:// resource URIs, fetch the latest version available
 * for each unique resource ID. Returns a map of `resourceUri → latestVersion`.
 *
 * Used to detect stale references in agents (workflow URIs) and
 * workflows (extension config URIs).
 */
export function useLatestVersions(resourceUris: string[]) {
  return useQuery({
    queryKey: ["latest-versions", ...resourceUris.slice().sort()],
    queryFn: async () => {
      const result: Record<string, number> = {};
      // Deduplicate by resource ID (ignoring version)
      const seen = new Set<string>();
      const toFetch: { uri: string; id: string; store: string; plural: string }[] = [];

      for (const uri of resourceUris) {
        if (!uri || !uri.includes("://")) continue;
        try {
          const { id } = parseResourceUri(uri);
          if (seen.has(id)) continue;
          seen.add(id);
          // Extract store and plural from the URI path
          const normalised = uri.startsWith("eddi://")
            ? uri.replace("eddi://", "http://")
            : uri;
          const parsed = new URL(normalised, "http://dummy");
          const segments = parsed.pathname.split("/").filter(Boolean);
          if (segments.length >= 3) {
            toFetch.push({ uri, id, store: segments[0]!, plural: segments[1]! });
          }
        } catch {
          // Skip unparseable URIs
        }
      }

      // Fetch latest version for each resource
      await Promise.all(
        toFetch.map(async ({ id, store, plural }) => {
          try {
            const descriptors = await api.get<AgentDescriptor[]>(
              `/${store}/${plural}/descriptors?filter=${id}&includePreviousVersions=true`
            );
            // Find the max version
            let maxVersion = 1;
            for (const d of descriptors) {
              try {
                // `filter` is a substring search over names and descriptions, so
                // the result can hold other resources whose text merely mentions
                // this id. Only this resource's own versions count.
                const { id: descriptorId, version } = parseResourceUri(d.resource);
                if (descriptorId !== id) continue;
                if (version > maxVersion) maxVersion = version;
              } catch {
                // skip
              }
            }
            result[id] = maxVersion;
          } catch {
            // Skip resources that can't be fetched
          }
        })
      );

      return result;
    },
    enabled: resourceUris.length > 0,
    staleTime: 30_000, // Cache for 30s to avoid excessive requests
  });
}

/**
 * Given a resource URI, extract the version and ID for comparison
 * with the latest version map.
 */
export function getVersionInfo(uri: string): { id: string; version: number } | null {
  try {
    return parseResourceUri(uri);
  } catch {
    return null;
  }
}
