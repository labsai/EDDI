import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { isApiError } from "@/lib/api-client";
import {
  getEnrichedConnectionDescriptors,
  getConnection,
  createConnection,
  updateConnection,
  deleteConnection,
  duplicateConnection,
  listMyConnections,
  authorizeConnection,
  disconnectConnection,
  type ConnectionConfiguration,
} from "@/lib/api/connections";

const CONNECTIONS_KEY = ["connections"] as const;

/**
 * The linked-accounts list.
 *
 * A child of `["connections"]` on purpose: deleting a connection deletes its
 * grants, so a config mutation invalidating the parent takes this with it.
 */
export const MINE_KEY = [...CONNECTIONS_KEY, "mine"] as const;

/**
 * Whether a failed query might succeed on a second attempt.
 *
 * Only two failures can: the network (`ApiClient` reports it as status 0) and a
 * 5xx, a proxy or store that is down. Every 4xx is the server's definitive
 * answer — a 400 or 409 replayed is the same 400 or 409, later — so an
 * allow-list of transient failures, not a deny-list of final ones. The deny-list
 * this replaced (401/403/404) retried every other 4xx.
 *
 * An error that is not an API error at all (a response that did not parse)
 * is a bug, not a blip, and is not retried either.
 */
function isTransientFailure(error: unknown): boolean {
  return isApiError(error) && (error.status === 0 || error.status >= 500);
}

// ─── Admin CRUD ─────────────────────────────────────────────────

/**
 * The connection configurations, enriched for the list.
 *
 * `enabled` exists for the per-user page, which wants this list only when the
 * viewer is an admin — asking anyway would spend a guaranteed 403 (and an
 * audit-log entry) on every page view to render nothing.
 */
export function useConnectionDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  enabled = true,
) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: [...CONNECTIONS_KEY, "enriched", { limit, index, filter }],
    queryFn: async () => {
      const rows = await getEnrichedConnectionDescriptors(limit, index, filter);
      // The enrichment already GET the full document for every row. Seeding the
      // detail page's cache with it turns opening a connection into zero
      // requests instead of re-downloading, a moment later, a document that was
      // in memory the whole time.
      for (const row of rows) {
        if (row.config) {
          queryClient.setQueryData(
            [...CONNECTIONS_KEY, row.id, row.version],
            row.config,
          );
        }
      }
      return rows;
    },
    enabled,
    /**
     * Connection documents are versioned and change only through this UI, and
     * every mutation here invalidates `["connections"]`. Without a staleTime the
     * default 30s window expires between a list → detail → back trip and replays
     * the whole 1 + N fan-out on return.
     */
    staleTime: 5 * 60_000,
    /**
     * A 403 is an answer, not a failure to retry. Retrying it three times
     * delays the "you are not an eddi-admin" screen by several seconds and
     * puts three refusals in the server's audit log for one page view. The same
     * is true of every other 4xx, so only a network failure or a 5xx is retried.
     */
    retry: (failureCount, error) => failureCount < 2 && isTransientFailure(error),
  });
}

export function useConnection(id: string, version?: number) {
  return useQuery({
    queryKey: [...CONNECTIONS_KEY, id, version],
    queryFn: () => getConnection(id, version),
    enabled: !!id,
  });
}

/**
 * The version a save's `Location` names, or null when it does not name one.
 *
 * Deliberately stricter than `parseConnectionResourceUri`, which defaults a
 * missing or unparseable version to 1. That default is right for reading a
 * descriptor and wrong here: it turns "the server said something we do not
 * understand" into "file this document as version 1", so a garbled header
 * would overwrite v1's cache entry with a *newer* document and hand it to
 * whoever opened that version next. Refusing to guess costs nothing — the page
 * simply fetches, which is what it did before any of this seeding existed.
 */
function versionFromLocation(location: string): number | null {
  let url: URL;
  try {
    url = new URL(location.replace("eddi://", "http://"), "http://dummy");
  } catch {
    return null;
  }
  const raw = url.searchParams.get("version");
  if (raw === null) return null;
  const version = Number.parseInt(raw, 10);
  return Number.isNaN(version) ? null : version;
}

export function useCreateConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: ConnectionConfiguration) => createConnection(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONNECTIONS_KEY });
    },
  });
}

export function useUpdateConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      config,
    }: {
      id: string;
      version: number;
      config: ConnectionConfiguration;
    }) => updateConnection(id, version, config),
    onSuccess: (result, { id, config }) => {
      // Seed the version this save just created — BEFORE the invalidation.
      //
      // A successful PUT returns the new version in its Location, and the
      // detail page follows it, which changes its query key. Without a seed
      // that key has no data, so the page drops to its loading skeleton and
      // fetches back the document it had itself just sent: a flash on every
      // save, and a moment in which the saved edits are not on screen.
      //
      // The order is the whole trick. `invalidateQueries` only marks the
      // queries that exist when it runs, and the global `staleTime` is 30s —
      // so seeding afterwards produces a *fresh* entry that no observer will
      // refetch, and anything the server normalised on write stays invisible
      // for half a minute. Seeding first means the invalidation catches this
      // key too: the page renders the seed immediately and the refetch that
      // reconciles it with the server happens behind that.
      const location = (result as { location?: string } | undefined)?.location;
      const newVersion = location ? versionFromLocation(location) : null;
      if (newVersion !== null) {
        queryClient.setQueryData([...CONNECTIONS_KEY, id, newVersion], config);
      }

      queryClient.invalidateQueries({ queryKey: CONNECTIONS_KEY });
    },
  });
}

export function useDeleteConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      permanent,
    }: {
      id: string;
      version: number;
      permanent?: boolean;
    }) => deleteConnection(id, version, permanent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONNECTIONS_KEY });
    },
  });
}

export function useDuplicateConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      duplicateConnection(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONNECTIONS_KEY });
    },
  });
}

// ─── Per-user grants ────────────────────────────────────────────

/**
 * The calling user's linked accounts.
 *
 * Its 4xx failures are final answers and are not retried: a 404 means the
 * feature is off, a 403 means there is no verified identity, and a 400 or 409
 * is the server refusing the request as sent. None improves on a second
 * attempt; the first two are states the page renders deliberately rather than
 * errors it hides.
 *
 * A 5xx or a network failure is an outage, and gets one retry before the panel
 * shows its error state with a Retry button. Retrying the definitive answers
 * would only delay them; not retrying the transient ones turned a proxy blip
 * into a page that looked deliberately broken.
 */
export function useMyConnections(enabled = true) {
  return useQuery({
    queryKey: MINE_KEY,
    queryFn: listMyConnections,
    enabled,
    retry: (failureCount, error) => failureCount < 1 && isTransientFailure(error),
  });
}

/**
 * Begin linking an account.
 *
 * Returns the provider URL; it does **not** navigate. The caller decides when
 * the page may leave, because the page leaving is the point at which any
 * unsaved state is lost.
 */
export function useAuthorizeConnection() {
  return useMutation({
    mutationFn: ({ name, returnTo }: { name: string; returnTo: string }) =>
      authorizeConnection(name, returnTo),
  });
}

export function useDisconnectConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ name }: { name: string }) => disconnectConnection(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MINE_KEY });
    },
  });
}
