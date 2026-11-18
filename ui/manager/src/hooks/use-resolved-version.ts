import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";

/**
 * The version a detail page should open: the one on the URL when it is a valid
 * positive integer, otherwise the resource's CURRENT version looked up from the
 * backend.
 *
 * A missing or malformed `?version` used to be read as 1, so a bare link opened
 * the resource's first version and the first save conflicted. While the lookup is
 * in flight `version` is undefined (callers keep their fetch disabled rather
 * than reading version 1 in the meantime), and a failed lookup is reported as
 * `lookupFailed` with `retry` — never turned into version 1 on a page that
 * writes.
 *
 * The looked-up version is held for the rest of the visit to this resource. The
 * pages seed their draft per version, so a background refetch (window focus)
 * that returned another client's newer version used to replace an open draft
 * without asking. The version moves only when the URL names one — which is what
 * the pages' own saves do — or when the page opens another resource. A cached
 * answer from an earlier visit is not taken as current: the lookup runs again
 * on mount and the version is pinned from that answer.
 */
export function useResolvedVersion(
  queryKey: readonly unknown[],
  id: string | undefined,
  urlVersion: string | null,
  fetchCurrent: (id: string) => Promise<number>,
): { version: number | undefined; lookupFailed: boolean; retry: () => void } {
  const explicit = urlVersion != null && /^[1-9]\d*$/.test(urlVersion) ? Number(urlVersion) : undefined;
  /** The version looked up for this resource, kept for the visit. */
  const [pinned, setPinned] = useState<{ id: string; version: number } | null>(null);
  const pinnedVersion = id !== undefined && pinned?.id === id ? pinned.version : undefined;
  const { data, isError, isFetchedAfterMount, isFetching, refetch } = useQuery({
    queryKey: [...queryKey, id, "currentVersion"],
    queryFn: () => fetchCurrent(id!),
    // Once pinned, nothing may move it: no focus or interval refetch at all.
    enabled: !!id && explicit === undefined && pinnedVersion === undefined,
    refetchOnMount: "always",
  });
  const fresh = isFetchedAfterMount && !isFetching && typeof data === "number" && data > 0 ? data : undefined;
  // Adjusting state while rendering (React's documented pattern for state
  // derived from a changing input): the page sees the pinned value at once.
  if (id !== undefined && explicit !== undefined && pinnedVersion !== explicit) {
    // The version the URL moved to (a save, a pick) is the one a bare link
    // returns to during this visit — not the one looked up before it.
    setPinned({ id, version: explicit });
  } else if (id !== undefined && explicit === undefined && pinnedVersion === undefined && fresh !== undefined) {
    setPinned({ id, version: fresh });
  }
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);
  if (explicit !== undefined) return { version: explicit, lookupFailed: false, retry };
  const version = pinnedVersion ?? fresh;
  if (version !== undefined) return { version, lookupFailed: false, retry };
  return { version: undefined, lookupFailed: isError && !isFetching, retry };
}
