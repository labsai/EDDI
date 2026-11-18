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
});
