import { useCallback } from "react";
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
 */
export function useResolvedVersion(
  queryKey: readonly unknown[],
  id: string | undefined,
  urlVersion: string | null,
  fetchCurrent: (id: string) => Promise<number>,
): { version: number | undefined; lookupFailed: boolean; retry: () => void } {
  const explicit = urlVersion != null && /^[1-9]\d*$/.test(urlVersion) ? Number(urlVersion) : undefined;
  const { data, isError, refetch } = useQuery({
    queryKey: [...queryKey, id, "currentVersion"],
    queryFn: () => fetchCurrent(id!),
    enabled: !!id && explicit === undefined,
    staleTime: 30_000,
  });
  const retry = useCallback(() => {
    void refetch();
  }, [refetch]);
  if (explicit !== undefined) return { version: explicit, lookupFailed: false, retry };
  if (typeof data === "number" && data > 0) return { version: data, lookupFailed: false, retry };
  return { version: undefined, lookupFailed: isError, retry };
}
