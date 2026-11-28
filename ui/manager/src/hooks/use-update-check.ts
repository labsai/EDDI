import { useCallback, useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { getErrorMessage } from "@eddi/app-kit/lib/api-client";
import { getUpdateCheckSettings, setUpdateCheckEnabled } from "@/lib/api/update-check-settings";
import { getEddiVersion, UNKNOWN_VERSION } from "@/lib/api/system";
import { isUpdateUiVisible } from "@eddi/app-kit/lib/brand";
import { useBrand } from "@eddi/app-kit/hooks/use-brand";
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

export const UPDATE_CHECK_QUERY_KEY = ["eddi-update-check"] as const;
export const EDDI_VERSION_QUERY_KEY = ["eddi-version"] as const;
export const UPDATE_CHECK_SETTINGS_QUERY_KEY = ["eddi-update-check-settings"] as const;

// ─── Auto-check switch (server-side) ─────────────────────────────────────────
//
// The switch lives on the backend, not in this browser: the operator's
// `EDDI_UPDATE_CHECK_ENABLED=false` has to stop the check before anyone can
// open the Manager, and an administrator's choice has to hold for every browser
// and node. The checkbox and the banner share one query, so ticking the box
// updates both at once.

/**
 * Read/write the server's update-check switch.
 *
 * `enabled` is false until the server has answered (and when it cannot be
 * reached): nothing leaves the browser on a guess. `locked` means the operator
 * switched the check off by property, which no one can undo from here.
 */
export function useUpdateCheckSwitch() {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: UPDATE_CHECK_SETTINGS_QUERY_KEY,
    queryFn: getUpdateCheckSettings,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const mutation = useMutation({
    mutationFn: setUpdateCheckEnabled,
    onSuccess: (next) => queryClient.setQueryData(UPDATE_CHECK_SETTINGS_QUERY_KEY, next),
    onError: (e) => toast.error(getErrorMessage(e)),
  });

  return {
    enabled: data?.enabled ?? false,
    locked: data?.lockedByConfig ?? false,
    /** The stored switch alone — what the checkbox shows while the config locks it off. */
    stored: data?.uiEnabled ?? false,
    setEnabled: (next: boolean) => mutation.mutate(next),
    saving: mutation.isPending,
  };
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
  /** The operator switched the check off by property — it cannot be turned on here. */
  autoCheckLocked: boolean;
  /** The stored switch, which is what the checkbox shows. */
  autoCheckStored: boolean;
  setAutoCheck: (next: boolean) => void;
  /** A change to the switch is being saved. */
  autoCheckSaving: boolean;
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
 * Check for a newer EDDI release.
 *
 * Two ways in, one shared cache, so the button and the checkbox can never
 * disagree about what the latest release is:
 *
 * - **Manual** — `checkNow()` refetches even while the query is disabled.
 * - **Automatic** — with the server's switch on (the default), the query is enabled and
 *   `staleTime: Infinity` holds it to exactly one request for the lifetime of
 *   the QueryClient, which is the lifetime of the page. That is what "check
 *   once per reload" means: a reload builds a new client and checks again.
 *
 * Exactly one host is ever contacted, api.github.com. The Docker image is
 * derived from the release rather than looked up — see `updates.ts` for why
 * that is accurate rather than a shortcut.
 *
 * With the switch off (by the operator's property or by an administrator) and no
 * button press, nothing is ever sent.
 */
export function useUpdateCheck(): UpdateCheckResult {
  const {
    enabled: autoCheck,
    locked: autoCheckLocked,
    stored: autoCheckStored,
    setEnabled: setAutoCheck,
    saving: autoCheckSaving,
  } = useUpdateCheckSwitch();
  const updateUi = isUpdateUiVisible(useBrand());
  const { data: installedVersion, isLoading: installedVersionLoading } = useEddiVersion();

  const query = useQuery({
    queryKey: UPDATE_CHECK_QUERY_KEY,
    queryFn: fetchLatestEddiRelease,
    // An enabled switch must not reach GitHub on a deployment whose
    // brand hides the update UI (white-label plan, R5): nothing would show it.
    enabled: autoCheck && updateUi,
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
    autoCheckLocked,
    autoCheckStored,
    setAutoCheck,
    autoCheckSaving,
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
