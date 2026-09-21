import { useCallback, useEffect, useMemo, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  getWorkspaceInfo,
  principalOf,
  WORKSPACES_UNAVAILABLE,
  type SpaceInfo,
  type WorkspaceInfo,
} from "@/lib/api/workspaces";

/**
 * Where the chosen space is remembered.
 *
 * Per browser, not per server: which workspace you were last looking at is a
 * view preference, and round-tripping it through the backend would make an
 * ordinary UI choice look like account state.
 */
const STORAGE_KEY = "eddi.workspace.space";

/** The sentinel for "everything I can reach", which is also the default. */
export const ALL_SPACES = "";

/**
 * The choice for this session, used only when storage is unreachable.
 *
 * Storage stays authoritative whenever it works — otherwise a test that clears
 * it would get a store still holding the previous test's choice. This is the
 * fallback for private mode, blocked site data and embedded webviews, where an
 * earlier version left the switcher completely inert: every read went through
 * to storage, storage threw, and the snapshot never changed no matter what was
 * clicked.
 */
let sessionChoice = ALL_SPACES;

function readStored(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? ALL_SPACES;
  } catch {
    return sessionChoice;
  }
}

function writeStored(spaceId: string) {
  sessionChoice = spaceId;
  try {
    if (spaceId === ALL_SPACES) localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, spaceId);
  } catch {
    // Not fatal: `sessionChoice` above already holds it for this session.
  }
}

/**
 * The chosen space, shared by every component that asks.
 *
 * <h3>Why this is not `useState`</h3> The switcher lives in the top bar and the
 * listing lives in the page — two unrelated trees, two calls to this hook. With
 * per-hook state each got its own copy, so choosing a space updated the
 * switcher's label and the listing never heard about it: the request went out
 * without `?space=` and the filter appeared to do nothing. An E2E run caught
 * that by watching the actual request; no unit test could, because each renders
 * one consumer.
 *
 * A module-level store read through `useSyncExternalStore` fixes it without a
 * provider, which matters because `useSpaces` is called from components that
 * share no common ancestor below the app root.
 */
const spaceStore = {
  listeners: new Set<() => void>(),

  subscribe(listener: () => void) {
    spaceStore.listeners.add(listener);
    // Another tab of the same app is another consumer of the same preference.
    window.addEventListener("storage", spaceStore.onStorage);
    return () => {
      spaceStore.listeners.delete(listener);
      if (spaceStore.listeners.size === 0) {
        window.removeEventListener("storage", spaceStore.onStorage);
      }
    };
  },

  // Reads through to storage rather than caching a copy at module load. The
  // snapshot is a string, so React compares it by value and re-renders only on
  // a real change — and a test that clears localStorage gets a store that
  // actually forgot, instead of one still holding the previous test's choice.
  getSnapshot() {
    return readStored();
  },

  set(spaceId: string) {
    if (readStored() === spaceId) return;
    writeStored(spaceId);
    spaceStore.notify();
  },

  onStorage(event: StorageEvent) {
    if (event.key === null || event.key === STORAGE_KEY) spaceStore.notify();
  },

  notify() {
    spaceStore.listeners.forEach((listener) => listener());
  },
};

export interface UseSpacesResult {
  /**
   * Whether the backend enforces workspaces at all.
   *
   * Every workspace affordance is gated on this. A deployment with the feature
   * off must look exactly like the release before it — a Share dialog that
   * cannot change anything is worse than no Share button.
   */
  enabled: boolean;
  /**
   * The caller's principal as the backend records it — the value stamped as
   * `ownerId`. Compare against this, not the token's display name.
   */
  principal: string | null;
  /** Whether this caller sees other people's resources (admin, or feature off). */
  seesEverything: boolean;
  /** Every space this user can reach, personal first. */
  spaces: SpaceInfo[];
  /** The active space id, or {@link ALL_SPACES} for no narrowing. */
  activeSpace: string;
  setActiveSpace: (spaceId: string) => void;
  /** The active space's descriptor, or null when showing everything. */
  active: SpaceInfo | null;
  /**
   * Whether a switcher is worth showing at all. One space means the control
   * would only ever offer the view the user already has.
   */
  hasChoice: boolean;
  /** True until the first answer arrives, so callers can avoid a flash. */
  isLoading: boolean;
}

/**
 * The spaces the current user can reach and which one the UI is filtered to.
 *
 * <h3>The server is the source of truth, deliberately</h3> An earlier version
 * derived the space list from the Keycloak token client-side. That is easy to
 * get subtly wrong — a space id encoded differently does not throw, it selects
 * a workspace matching nothing, which renders as "you have no agents". Asking
 * the backend removes the second implementation of the encoding along with that
 * whole class of silent failure, and it is the only way to learn whether
 * enforcement is even switched on, which has no evidence in the data.
 *
 * The active space is a *narrowing*, never a widening: the backend scopes every
 * listing to what the caller may see regardless, and asking for a space you
 * cannot reach returns nothing rather than granting it. So this is safe to
 * treat as pure presentation.
 */
export function useSpaces(): UseSpacesResult {
  const { data, isLoading, status } = useQuery<WorkspaceInfo>({
    queryKey: ["workspaces", "info"],
    queryFn: getWorkspaceInfo,
    // Group membership and the enforcement flag change on the order of a
    // deployment, not a page view. Refetching either on every mount would be
    // noise on every screen in the app.
    staleTime: 5 * 60 * 1000,
    // `staleTime` does not apply to a query in error state, and OwnershipBadge
    // is one observer PER AGENT CARD — so a persistently failing /workspaces
    // (a 403 for a role outside the two this endpoint allows, say) would fire
    // another request for every card in every infinite-scroll batch. One retry
    // covers a blip; not remounting covers the rest.
    retry: 1,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // An error resolves to "no workspaces" rather than blocking the page: the
  // rest of the Manager works fine without them, and every real restriction is
  // enforced server-side no matter what is drawn here.
  const info = data ?? WORKSPACES_UNAVAILABLE;
  const spaces = useMemo(() => info.spaces, [info]);

  const activeSpace = useSyncExternalStore(spaceStore.subscribe, spaceStore.getSnapshot);

  // A remembered space the user can no longer reach — they left the group —
  // would filter everything away with no visible cause. Drop back to showing
  // everything instead.
  //
  // Gated on a SUCCESSFUL answer, not merely on "not loading". An error also
  // leaves `spaces` empty, and treating that as "you cannot reach it" deleted
  // the stored preference outright: a 502 during a deploy, or one expired-token
  // race, and the user's workspace choice was gone for good. `getWorkspaceInfo`
  // deliberately rethrows non-404s so a real problem is not mistaken for
  // "workspaces are off" — this must not then quietly make that same mistake,
  // and destroy state doing it.
  //
  // Enforcement being off is likewise not a reason to forget. The narrowing is
  // already not applied in that state, and an operator toggling the flag should
  // not silently reset everyone's view preference.
  useEffect(() => {
    if (activeSpace === ALL_SPACES) return;
    if (!data || !data.enabled) return;
    if (spaces.some((s) => s.id === activeSpace)) return;
    spaceStore.set(ALL_SPACES);
  }, [data, spaces, activeSpace]);

  const setActiveSpace = useCallback((spaceId: string) => spaceStore.set(spaceId), []);

  const active = useMemo(
    () => spaces.find((s) => s.id === activeSpace) ?? null,
    [spaces, activeSpace]
  );

  // A space narrowing is meaningless once we KNOW nothing is enforced — everyone
  // already sees everything.
  //
  // Three states, not two. "We have not asked yet" is not "it is off": dropping
  // the narrowing while the query is in flight makes every listing fetch
  // unfiltered and refetch a moment later, which the user sees as their own
  // filter briefly not applying. But a query that has SETTLED into failure is
  // not still in flight either — carrying on narrowing there would send
  // `?space=` while this same hook reports `enabled: false` and hides the
  // switcher, leaving an invisible filter with no control to clear it.
  const narrowingApplies = data ? data.enabled : status === "pending";

  return {
    enabled: info.enabled,
    principal: principalOf(info),
    seesEverything: info.seesEverything,
    spaces,
    activeSpace: narrowingApplies ? activeSpace : ALL_SPACES,
    setActiveSpace,
    active,
    hasChoice: info.enabled && spaces.length > 1,
    isLoading,
  };
}
