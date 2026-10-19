import type { QueryClient, QueryKey } from "@tanstack/react-query";

/**
 * Optimistic cache edits for TanStack Query mutations.
 *
 * A click should change what the user sees at once, not after the request and
 * the refetch behind it. `patchQueries` rewrites every cached query under a key
 * prefix in `onMutate` and returns the snapshot `onError` hands back to
 * `restoreQueries`; the usual `invalidateQueries` in `onSettled` then brings
 * the cache back in line with the server.
 */
export type QuerySnapshot = Array<[QueryKey, unknown]>;

export async function patchQueries<T>(
  qc: QueryClient,
  queryKey: QueryKey,
  update: (old: T) => T,
): Promise<QuerySnapshot> {
  // An in-flight refetch would land after the patch and overwrite it.
  await qc.cancelQueries({ queryKey });
  const snapshot = qc.getQueriesData<T>({ queryKey });
  for (const [key, data] of snapshot) {
    if (data !== undefined) qc.setQueryData<T>(key, update(data));
  }
  return snapshot;
}

export function restoreQueries(qc: QueryClient, snapshot: QuerySnapshot | undefined): void {
  if (!snapshot) return;
  for (const [key, data] of snapshot) qc.setQueryData(key, data);
}

/** Apply `fn` to `data` when it is an array, leave anything else untouched. */
export function mapIfArray<T>(data: unknown, fn: (items: T[]) => T[]): unknown {
  return Array.isArray(data) ? fn(data as T[]) : data;
}
