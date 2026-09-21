import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ALL_SPACES, useSpaces } from "@/hooks/use-spaces";
import * as workspacesApi from "@/lib/api/workspaces";
import { WORKSPACES_UNAVAILABLE, type WorkspaceInfo } from "@/lib/api/workspaces";

const PERSONAL = { id: "user:alice", kind: "personal" as const, label: "alice" };
const TEAM = { id: "team:engineering", kind: "team" as const, label: "engineering" };

function info(overrides: Partial<WorkspaceInfo> = {}): WorkspaceInfo {
  return {
    enabled: true,
    principal: "alice",
    defaultSpace: PERSONAL.id,
    spaces: [PERSONAL, TEAM],
    seesEverything: false,
    ...overrides,
  };
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function render() {
  return renderHook(() => useSpaces(), { wrapper: createWrapper() });
}

describe("useSpaces", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("offers the spaces the server reports, personal first", async () => {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    const { result } = render();

    await waitFor(() => expect(result.current.spaces).toHaveLength(2));
    expect(result.current.spaces.map((s) => s.id)).toEqual([PERSONAL.id, TEAM.id]);
    expect(result.current.hasChoice).toBe(true);
    expect(result.current.principal).toBe("alice");
  });

  it("hides the switcher when there is only one space to switch to", async () => {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ spaces: [PERSONAL] }));

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.hasChoice).toBe(false);
  });

  it("offers nothing at all when the deployment does not enforce workspaces", async () => {
    // The load-bearing case: ownership is still *recorded* while enforcement is
    // off, so the fields are present and a UI reading them alone would draw a
    // switcher and a Share button that cannot do anything.
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(
      info({ enabled: false, seesEverything: true })
    );

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.enabled).toBe(false);
    expect(result.current.hasChoice).toBe(false);
  });

  it("does not apply a remembered narrowing while enforcement is off", async () => {
    // Otherwise a preference saved before an operator turned the feature off
    // keeps sending ?space= to a backend that ignores it — the filter appears to
    // work and quietly does nothing.
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ enabled: false }));

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.activeSpace).toBe(ALL_SPACES);
  });

  it("forgets a remembered space the user can no longer reach", async () => {
    // They left the group. Keeping the filter would show an empty list with no
    // visible cause.
    localStorage.setItem("eddi.workspace.space", "team:finance");
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    const { result } = render();

    // Assert on the stored value, not the returned one: the getter also reports
    // ALL_SPACES while the answer is still in flight, so waiting on it alone
    // would pass before the reset had happened at all.
    await waitFor(() => expect(localStorage.getItem("eddi.workspace.space")).toBeNull());
    expect(result.current.activeSpace).toBe(ALL_SPACES);
  });

  it("keeps a remembered space that is still reachable", async () => {
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    const { result } = render();

    await waitFor(() => expect(result.current.spaces).toHaveLength(2));
    expect(result.current.activeSpace).toBe(TEAM.id);
    expect(result.current.active?.label).toBe("engineering");
  });

  it("does not clear the remembered space before the answer arrives", async () => {
    // A pending query has no spaces yet. Resetting on that would drop the user's
    // choice on every page load, which reads as the setting not sticking.
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockImplementation(
      () => new Promise(() => {}) as Promise<WorkspaceInfo>
    );

    const { result } = render();

    expect(result.current.isLoading).toBe(true);
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("remembers a chosen space across mounts", async () => {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    const { result } = render();
    await waitFor(() => expect(result.current.spaces).toHaveLength(2));

    act(() => result.current.setActiveSpace(TEAM.id));

    expect(result.current.activeSpace).toBe(TEAM.id);
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("shares the chosen space with every other consumer of the hook", async () => {
    // The switcher lives in the top bar and the listing lives in the page —
    // two unrelated trees, two calls to this hook. Per-hook `useState` gave
    // each its own copy, so choosing a space updated the switcher's label and
    // the listing never heard: the request went out with no `?space=` and the
    // filter appeared to do nothing at all. Rendering two consumers is the
    // whole point of this test; one consumer cannot fail it.
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    const wrapper = createWrapper();
    const switcher = renderHook(() => useSpaces(), { wrapper });
    const listing = renderHook(() => useSpaces(), { wrapper });

    await waitFor(() => expect(switcher.result.current.spaces).toHaveLength(2));
    await waitFor(() => expect(listing.result.current.spaces).toHaveLength(2));

    act(() => switcher.result.current.setActiveSpace(TEAM.id));

    expect(switcher.result.current.activeSpace).toBe(TEAM.id);
    expect(listing.result.current.activeSpace).toBe(TEAM.id);
  });

  it("keeps the remembered space when the request fails", async () => {
    // A 502 during a deploy, or one expired-token race. An earlier version
    // treated the resulting empty space list as "you cannot reach it any more"
    // and DELETED the stored preference — permanently, for a transient fault.
    // `getWorkspaceInfo` rethrows non-404s precisely so a real problem is not
    // mistaken for "workspaces are off"; this must not then make that mistake
    // itself, let alone destroy state doing it.
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockRejectedValue(new Error("boom"));

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("stops applying the narrowing once the request has settled into failure", async () => {
    // Still in flight is not the same as failed. While pending the narrowing is
    // kept, so the listing does not fetch unfiltered and refetch a moment later
    // — but once the query has settled with no answer, this hook reports
    // `enabled: false` and hides the switcher, so continuing to send `?space=`
    // would leave an invisible filter with no control anywhere to clear it.
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockRejectedValue(new Error("boom"));

    const { result } = render();

    await waitFor(() => expect(result.current.activeSpace).toBe(ALL_SPACES));
    // The preference itself survives — only its application is suspended.
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("keeps applying the narrowing while the answer is still in flight", async () => {
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockImplementation(
      () => new Promise(() => {}) as Promise<WorkspaceInfo>
    );

    const { result } = render();

    expect(result.current.isLoading).toBe(true);
    expect(result.current.activeSpace).toBe(TEAM.id);
  });

  it("keeps the remembered space when enforcement is merely switched off", async () => {
    // The narrowing is already not applied in that state, so forgetting it as
    // well only means an operator toggling the flag silently resets everyone's
    // view preference.
    localStorage.setItem("eddi.workspace.space", TEAM.id);
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info({ enabled: false, spaces: [] }));

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.activeSpace).toBe(ALL_SPACES);
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("still switches when localStorage is unavailable", async () => {
    // Private mode, blocked site data, an embedded webview. Reading through to
    // storage on every snapshot made the switcher completely inert there: the
    // read threw, the snapshot never changed, and clicking an option did
    // nothing at all.
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(info());

    try {
      const { result } = render();
      await waitFor(() => expect(result.current.spaces).toHaveLength(2));

      act(() => result.current.setActiveSpace(TEAM.id));

      expect(result.current.activeSpace).toBe(TEAM.id);
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });

  it("degrades to no workspaces when the endpoint is unreachable", async () => {
    // An older backend 404s, which getWorkspaceInfo resolves rather than
    // rejects; anything else rejects and lands here. Either way the rest of the
    // Manager keeps working, and nothing that needs enforcement is drawn.
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockRejectedValue(new Error("boom"));

    const { result } = render();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.enabled).toBe(false);
    expect(result.current.spaces).toEqual([]);
    expect(result.current.principal).toBeNull();
    expect(result.current.seesEverything).toBe(WORKSPACES_UNAVAILABLE.seesEverything);
  });
});
