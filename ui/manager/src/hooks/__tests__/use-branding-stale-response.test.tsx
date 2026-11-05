import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { brandingKeys, useDeleteBrandAsset } from "@/hooks/use-branding";
import { applyBrandRevision } from "@/lib/brand-live";
import type { BrandingView } from "@/lib/api/branding";

vi.mock("@/lib/brand-live", () => ({ applyBrandRevision: vi.fn(() => Promise.resolve()) }));

const view = (revision: number, productName: string): BrandingView => ({
  document: { revision, productName },
  effective: { revision, branded: true, productName, shortName: productName, poweredBy: true, showUpdateCheck: true },
  locked: false,
  source: "database",
});

function setup(cached: BrandingView) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  qc.setQueryData(brandingKeys.all, cached);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, wrapper };
}

beforeEach(() => vi.mocked(applyBrandRevision).mockClear());

describe("branding writes answering out of order", () => {
  it("ignores a late response older than the cached revision", async () => {
    server.use(http.delete("*/administration/branding/assets/:slot", () => HttpResponse.json(view(4, "Stale"))));
    const { qc, wrapper } = setup(view(5, "Current"));
    const { result } = renderHook(() => useDeleteBrandAsset(), { wrapper });

    await result.current.mutateAsync("logo");

    expect(qc.getQueryData<BrandingView>(brandingKeys.all)?.effective.productName).toBe("Current");
    expect(applyBrandRevision).not.toHaveBeenCalled();
  });

  it("still caches and applies a response that is not older", async () => {
    server.use(http.delete("*/administration/branding/assets/:slot", () => HttpResponse.json(view(6, "Newer"))));
    const { qc, wrapper } = setup(view(5, "Current"));
    const { result } = renderHook(() => useDeleteBrandAsset(), { wrapper });

    await result.current.mutateAsync("logo");

    await waitFor(() => expect(applyBrandRevision).toHaveBeenCalledWith(6));
    expect(qc.getQueryData<BrandingView>(brandingKeys.all)?.effective.productName).toBe("Newer");
  });
});
