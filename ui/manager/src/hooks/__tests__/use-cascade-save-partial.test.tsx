import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("@/lib/api/cascade-save", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api/cascade-save")>(
    "@/lib/api/cascade-save",
  );
  return { ...actual, cascadeSaveResource: vi.fn() };
});

import { CascadeSaveError, cascadeSaveResource } from "@/lib/api/cascade-save";
import { useCascadeSave } from "@/hooks/use-resources";

/**
 * A cascade that failed partway still wrote new versions, and the page moves
 * onto them. The version lists had been refreshed only on success, so the
 * picker did not offer the version the page was now showing.
 */
describe("useCascadeSave — after a partial failure", () => {
  function setup() {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useCascadeSave("rules"), { wrapper });
    return { result, invalidate };
  }

  it("refreshes the resource and workflow lists", async () => {
    vi.mocked(cascadeSaveResource).mockRejectedValueOnce(
      new CascadeSaveError(new Error("agent conflict"), { newResourceVersion: 2, newWorkflowVersion: 3 }),
    );
    const { result, invalidate } = setup();

    await act(async () => {
      await result.current.mutateAsync({ id: "r1", version: 1, body: {} }).catch(() => undefined);
    });

    const keys = invalidate.mock.calls.map(([filters]) => filters?.queryKey?.[0]);
    expect(keys).toContain("resources");
    expect(keys).toContain("workflows");
  });

  it("refreshes nothing when nothing was written", async () => {
    vi.mocked(cascadeSaveResource).mockRejectedValueOnce(new Error("resource conflict"));
    const { result, invalidate } = setup();

    await act(async () => {
      await result.current.mutateAsync({ id: "r1", version: 1, body: {} }).catch(() => undefined);
    });

    expect(invalidate).not.toHaveBeenCalled();
  });
});
