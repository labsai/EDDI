import { describe, it, expect, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useResolvedVersion } from "@/hooks/use-resolved-version";

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useResolvedVersion", () => {
  it("uses the version on the URL without asking the backend", () => {
    const fetchCurrent = vi.fn();
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", "3", fetchCurrent), { wrapper: wrapper() });
    expect(result.current.version).toBe(3);
    expect(fetchCurrent).not.toHaveBeenCalled();
  });

  it("resolves the CURRENT version for a bare link instead of falling back to 1", async () => {
    const fetchCurrent = vi.fn().mockResolvedValue(7);
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", null, fetchCurrent), { wrapper: wrapper() });
    expect(result.current.version).toBeUndefined();
    await waitFor(() => expect(result.current.version).toBe(7));
  });

  it("treats a malformed ?version as absent", async () => {
    const fetchCurrent = vi.fn().mockResolvedValue(4);
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", "abc", fetchCurrent), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.version).toBe(4));
  });

  it("reports a failed lookup instead of guessing version 1", async () => {
    const fetchCurrent = vi.fn().mockRejectedValue(new Error("down"));
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", null, fetchCurrent), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.lookupFailed).toBe(true));
    expect(result.current.version).toBeUndefined();
  });

  it("keeps the resolved version when a later lookup answers with a newer one", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const Wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const fetchCurrent = vi.fn().mockResolvedValueOnce(3).mockResolvedValue(5);
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", null, fetchCurrent), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.version).toBe(3));

    // A window-focus refetch, an invalidation: another client saved v5 meanwhile.
    await client.refetchQueries({ queryKey: ["x"] });
    await client.invalidateQueries({ queryKey: ["x"] });
    expect(result.current.version).toBe(3);
  });

  it("follows the URL when it names a version, and a bare link then returns to that one", async () => {
    const fetchCurrent = vi.fn().mockResolvedValue(3);
    const { result, rerender } = renderHook(
      ({ v }: { v: string | null }) => useResolvedVersion(["x"], "a", v, fetchCurrent),
      { wrapper: wrapper(), initialProps: { v: null as string | null } },
    );
    await waitFor(() => expect(result.current.version).toBe(3));
    rerender({ v: "4" }); // the page's own save moved the URL on
    expect(result.current.version).toBe(4);
    rerender({ v: null }); // Back to the bare link
    expect(result.current.version).toBe(4);
  });

  it("does not take a cached answer from an earlier visit as current", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(["x", "a", "currentVersion"], 2);
    const Wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const fetchCurrent = vi.fn().mockResolvedValue(6);
    const { result } = renderHook(() => useResolvedVersion(["x"], "a", null, fetchCurrent), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.version).toBe(6));
  });

  it("looks the version up again for another resource", async () => {
    const fetchCurrent = vi.fn(async (id: string) => (id === "a" ? 3 : 9));
    const { result, rerender } = renderHook(
      ({ id }: { id: string }) => useResolvedVersion(["x"], id, null, fetchCurrent),
      { wrapper: wrapper(), initialProps: { id: "a" } },
    );
    await waitFor(() => expect(result.current.version).toBe(3));
    rerender({ id: "b" });
    await waitFor(() => expect(result.current.version).toBe(9));
  });
});
