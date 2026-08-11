import { useCallback, useMemo, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { getEddiVersion } from "@/lib/api/system";
import {
  fetchLatestDockerImage,
  fetchLatestEddiRelease,
  getImageStatus,
  getUpdateStatus,
  UpdateCheckError,
  type DockerImage,
  type EddiRelease,
  type ImageStatus,
  type UpdateCheckErrorReason,
  type UpdateStatus,
} from "@/lib/api/updates";

/** localStorage flag for the opt-in per-reload check. Off unless explicitly set. */
export const AUTO_UPDATE_CHECK_KEY = "eddi-auto-update-check";

export const UPDATE_CHECK_QUERY_KEY = ["eddi-update-check"] as const;
export const DOCKER_IMAGE_QUERY_KEY = ["eddi-docker-image"] as const;
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
  installedVersionLoading: boolean;
  latest: EddiRelease | undefined;
  status: UpdateStatus;
  /** Highest published Docker tag, or `undefined` if that lookup failed. */
  image: DockerImage | undefined;
  /** Whether the released version can actually be pulled yet. */
  imageStatus: ImageStatus;
  isChecking: boolean;
  /** `undefined` unless the GitHub lookup failed. */
  errorReason: UpdateCheckErrorReason | undefined;
  /** True when the Docker lookup failed but GitHub may still have answered. */
  imageLookupFailed: boolean;
  /** True once a check has produced either a result or an error this session. */
  hasChecked: boolean;
  /** Force a fresh check now, regardless of the auto-check preference. */
  checkNow: () => void;
}

/** Both lookups are opt-in, cached for the page's lifetime, and never retried. */
const CHECK_QUERY_OPTIONS = {
  staleTime: Infinity,
  gcTime: Infinity,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
  refetchOnMount: false,
  retry: false,
} as const;

/**
 * Opt-in check for a newer EDDI release.
 *
 * Two ways in, one shared cache, so the button and the checkbox can never
 * disagree about what the latest release is:
 *
 * - **Manual** — `checkNow()` refetches even while the queries are disabled.
 * - **Automatic** — with the preference on, the queries are enabled and
 *   `staleTime: Infinity` holds each to exactly one request for the lifetime of
 *   the QueryClient, which is the lifetime of the page. That is what "check
 *   once per reload" means: a reload builds a new client and checks again.
 *
 * The two sources are separate queries rather than one combined fetch so that
 * Docker Hub being unreachable still leaves the release answer intact — the
 * release is the part that decides whether an update exists at all.
 *
 * With the preference off and no button press, nothing is ever sent.
 */
export function useUpdateCheck(): UpdateCheckResult {
  const [autoCheck, setAutoCheck] = useAutoUpdateCheck();
  const { data: installedVersion, isLoading: installedVersionLoading } = useEddiVersion();

  const releaseQuery = useQuery({
    queryKey: UPDATE_CHECK_QUERY_KEY,
    queryFn: fetchLatestEddiRelease,
    enabled: autoCheck,
    ...CHECK_QUERY_OPTIONS,
  });

  const imageQuery = useQuery({
    queryKey: DOCKER_IMAGE_QUERY_KEY,
    queryFn: fetchLatestDockerImage,
    enabled: autoCheck,
    ...CHECK_QUERY_OPTIONS,
  });

  const { refetch: refetchRelease } = releaseQuery;
  const { refetch: refetchImage } = imageQuery;
  const checkNow = useCallback(() => {
    void refetchRelease();
    void refetchImage();
  }, [refetchRelease, refetchImage]);

  const status = useMemo(
    () => getUpdateStatus(installedVersion, releaseQuery.data?.version),
    [installedVersion, releaseQuery.data?.version],
  );

  const imageStatus = useMemo(
    () => getImageStatus(releaseQuery.data?.version, imageQuery.data?.version),
    [releaseQuery.data?.version, imageQuery.data?.version],
  );

  const errorReason = releaseQuery.error
    ? releaseQuery.error instanceof UpdateCheckError
      ? releaseQuery.error.reason
      : "failed"
    : undefined;

  return {
    autoCheck,
    setAutoCheck,
    installedVersion,
    installedVersionLoading,
    latest: releaseQuery.data,
    status,
    image: imageQuery.data,
    imageStatus,
    isChecking: releaseQuery.isFetching || imageQuery.isFetching,
    errorReason,
    imageLookupFailed: imageQuery.isError,
    hasChecked:
      releaseQuery.data !== undefined ||
      releaseQuery.isError ||
      imageQuery.data !== undefined ||
      imageQuery.isError,
    checkNow,
  };
}
