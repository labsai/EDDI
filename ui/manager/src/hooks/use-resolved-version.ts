import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";

/**
 * The version a detail page should open: the one on the URL when it is a valid
 * positive integer, otherwise the resource's CURRENT version looked up from the
 * backend.
 *
 * A missing or malformed `?version` used to be read as 1, so a bare link opened
 * the resource's first version and the first save conflicted. A failed lookup
 * is never turned into version 1 on a page that writes, and the pages seed
 * their draft per version, so the version must not move under an open draft.
 *
 * States, per visit (a visit is the time the hook spends on one resource id):
 *
 * | state                           | `version`   | `lookupFailed` | lookup query           |
 * | ------------------------------- | ----------- | -------------- | ---------------------- |
 * | no id                           | undefined   | false          | disabled               |
 * | URL names a version             | URL version | false          | disabled; that version is pinned |
 * | bare link, lookup pending       | undefined   | false          | runs (callers keep their fetch disabled) |
 * | bare link, lookup succeeded     | pinned      | false          | disabled for the rest of the visit |
 * | bare link, lookup failed        | undefined   | true           | idle; `retry` re-runs it |
 *
 * Transitions:
 * - pending → succeeded: only an answer fetched during THIS visit that
 *   succeeded is pinned. A cached answer from an earlier visit is not taken as
 *   current (the lookup runs again, `staleTime: 0`, whatever the client's
 *   default), and when that re-run fails the cached value — which TanStack
 *   Query keeps in `data` next to the error — does not come back as the
 *   version: the state is "failed".
 * - failed → `retry` → pending → succeeded (pinned) or failed again.
 * - the same id re-rendered, refocused or invalidated: the pin is kept and the
 *   query stays disabled, so no refetch can deliver another client's newer
 *   version and reseed an open draft.
 * - the URL names a version (the page's own save, a version pick): that
 *   version is pinned, and a bare link during the same visit returns to it.
 * - the id changes: the pin is dropped and a new visit starts — also when the
 *   previous lookup never finished, and also when the page comes back to a
 *   resource it pinned earlier (that resource may have moved on meanwhile).
 */
export function useResolvedVersion(
  queryKey: readonly unknown[],
  id: string | undefined,
  urlVersion: string | null,
  fetchCurrent: (id: string) => Promise<number>,
): { version: number | undefined; lookupFailed: boolean; retry: () => void } {
  const explicit = urlVersion != null && /^[1-9]\d*$/.test(urlVersion) ? Number(urlVersion) : undefined;
  /** The resource of the current visit and the version pinned for it. */
  const [visit, setVisit] = useState<{ id: string | undefined; pinned: number | undefined }>({
    id,
    pinned: undefined,
  });
  const sameVisit = visit.id === id;
  const held = sameVisit ? visit.pinned : undefined;
  const { data, isSuccess, isError, isFetchedAfterMount, isFetching, refetch } = useQuery({
    queryKey: [...queryKey, id, "currentVersion"],
    queryFn: () => fetchCurrent(id!),
    // Once pinned, nothing may move it: no focus or interval refetch at all.
    enabled: !!id && explicit === undefined && held === undefined,
    // A cached answer is never current: every visit asks again, also when the
    // id changes back to a resource whose answer is still in the cache.
    staleTime: 0,
    refetchOnMount: "always",
  });
  // `isFetchedAfterMount` is per query for this observer, so it also restarts
  // on an id change. `isSuccess` matters: after a failed re-run the previous
  // answer is still in `data`.
  const looked =
    isSuccess && isFetchedAfterMount && !isFetching && typeof data === "number" && data > 0 ? data : undefined;
  let next = held;
  if (id !== undefined && explicit !== undefined) next = explicit;
  else if (id !== undefined && next === undefined && looked !== undefined) next = looked;
  // Adjusting state while rendering (React's documented pattern for state
  // derived from a changing input): the page sees the new value at once.
  if (!sameVisit || next !== held) setVisit({ id, pinned: next });
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);
  if (id === undefined) return { version: undefined, lookupFailed: false, retry };
  if (next !== undefined) return { version: next, lookupFailed: false, retry };
  return { version: undefined, lookupFailed: isError && !isFetching, retry };
}
