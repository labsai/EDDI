import { useCallback, useMemo, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { getEddiVersion, UNKNOWN_VERSION } from "@/lib/api/system";
import {
  dockerImageFor,
  fetchLatestEddiRelease,
  getUpdateStatus,
  UpdateCheckError,
  type DockerImage,
  type EddiRelease,
  type UpdateCheckErrorReason,
  type UpdateStatus,
} from "@/lib/api/updates";

/** localStorage flag for the opt-in per-reload check. Off unless explicitly set. */
export const AUTO_UPDATE_CHECK_KEY = "eddi-auto-update-check";

export const UPDATE_CHECK_QUERY_KEY = ["eddi-update-check"] as const;
export const EDDI_VERSION_QUERY_KEY = ["eddi-version"] as const;

// ─── Auto-check preference (shared external store) ───────────────────────────
//
// The checkbox and the banner live in different subtrees, so a plain
// useState-backed localStorage hook would leave them disagreeing until the next
// reload — ticking the box would fetch, but the banner would keep its stale
// `false` and stay hidden. A tiny external store keeps every subscriber on the
// same value the moment it changes, in this tab and in others.

const listeners = new Set<() => void>();

function subscribeAutoUpdateCheck(onChange: () => void): () => void {
  listeners.add(onChange);
  // `storage` only fires for *other* tabs; same-tab writes go through notify().
  window.addEventListener("storage", onChange);
  return () => {
    listeners.delete(onChange);
    window.removeEventListener("storage", onChange);
  };
}

/** Read straight from storage — a boolean needs no snapshot caching. */
function getAutoUpdateCheck(): boolean {
  try {
    return localStorage.getItem(AUTO_UPDATE_CHECK_KEY) === "true";
  } catch {
    return false; // storage unavailable — stay off, which is the safe default
  }
}

export function setAutoUpdateCheck(next: boolean): void {
  try {
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, String(next));
  } catch {
    /* storage unavailable — the value still applies for this page */
  }
  listeners.forEach((listener) => listener());
}

/** Read/write the "check on every reload" preference. */
export function useAutoUpdateCheck(): [boolean, (next: boolean) => void] {
  const value = useSyncExternalStore(
    subscribeAutoUpdateCheck,
    getAutoUpdateCheck,
    () => false,
  );
  return [value, setAutoUpdateCheck];
}

// ─── Installed version ───────────────────────────────────────────────────────

/**
 * The EDDI version this Manager is served by, read from the backend's OpenAPI
 * descriptor. Shared query key, so the sidebar footer and the update card cost
 * one request between them.
 */
export function useEddiVersion() {
  return useQuery({
    queryKey: EDDI_VERSION_QUERY_KEY,
    queryFn: getEddiVersion,
    staleTime: Infinity,
    retry: 1, // Don't retry infinitely if offline
  });
}

// ─── Update check ────────────────────────────────────────────────────────────

export interface UpdateCheckResult {
  autoCheck: boolean;
  setAutoCheck: (next: boolean) => void;
  /** Installed EDDI version, or `undefined` while it is still loading. */
  installedVersion: string | undefined;
  /**
   * The installed version only when it is an actual version — `null` while
   * loading, on failure, or when the backend reported `UNKNOWN_VERSION`.
   * Derived here so no consumer has to know the sentinel's spelling.
   */
  knownInstalledVersion: string | null;
  installedVersionLoading: boolean;
  latest: EddiRelease | undefined;
  status: UpdateStatus;
  /** The image that release is published as — derived, not a second request. */
  image: DockerImage | undefined;
  isChecking: boolean;
  /** `undefined` unless the last check failed. */
  errorReason: UpdateCheckErrorReason | undefined;
  /** True once a check has produced either a result or an error this session. */
  hasChecked: boolean;
  /** Force a fresh check now, regardless of the auto-check preference. */
  checkNow: () => void;
}

/**
 * Opt-in check for a newer EDDI release.
 *
 * Two ways in, one shared cache, so the button and the checkbox can never
 * disagree about what the latest release is:
 *
 * - **Manual** — `checkNow()` refetches even while the query is disabled.
 * - **Automatic** — with the preference on, the query is enabled and
 *   `staleTime: Infinity` holds it to exactly one request for the lifetime of
 *   the QueryClient, which is the lifetime of the page. That is what "check
 *   once per reload" means: a reload builds a new client and checks again.
 *
 * Exactly one host is ever contacted, api.github.com. The Docker image is
 * derived from the release rather than looked up — see `updates.ts` for why
 * that is accurate rather than a shortcut.
 *
 * With the preference off and no button press, nothing is ever sent.
 */
export function useUpdateCheck(): UpdateCheckResult {
  const [autoCheck, setAutoCheck] = useAutoUpdateCheck();
  const { data: installedVersion, isLoading: installedVersionLoading } = useEddiVersion();

  const query = useQuery({
    queryKey: UPDATE_CHECK_QUERY_KEY,
    queryFn: fetchLatestEddiRelease,
    enabled: autoCheck,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
    retry: false,
  });

  const { refetch } = query;
  const checkNow = useCallback(() => {
    void refetch();
  }, [refetch]);

  const status = useMemo(
    () => getUpdateStatus(installedVersion, query.data?.version),
    [installedVersion, query.data?.version],
  );

  const releaseVersion = query.data?.version;
  const image = useMemo(
    () => (releaseVersion ? dockerImageFor(releaseVersion) : undefined),
    [releaseVersion],
  );

  const errorReason = query.error
    ? query.error instanceof UpdateCheckError
      ? query.error.reason
      : "failed"
    : undefined;

  return {
    autoCheck,
    setAutoCheck,
    installedVersion,
    knownInstalledVersion:
      installedVersion && installedVersion !== UNKNOWN_VERSION ? installedVersion : null,
    installedVersionLoading,
    latest: query.data,
    status,
    image,
    isChecking: query.isFetching,
    errorReason,
    hasChecked: query.data !== undefined || query.isError,
    checkNow,
  };
}
