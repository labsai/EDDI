import { describe, expect, it } from "vitest";
import type { ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { HEALTH_AGENTS } from "@eddi/app-kit/test/mocks/health-fixtures";
import { useHealthSeries } from "../use-agent-health";

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>{children}</QueryClientProvider>
);

// Seven measures: two series calls, the second carrying only `m7`.
const MEASURES = ["m1", "m2", "m3", "m4", "m5", "m6", "m7"];

describe("useHealthSeries", () => {
  it("reports a failed chunk, keeps the trends of the others, and retries only the failed one", async () => {
    let fail = true;
    const calls: string[][] = [];
    server.use(
      http.get("*/agents/:agentId/health/series", ({ request }) => {
        const measures = new URL(request.url).searchParams.getAll("measure");
        calls.push(measures);
        if (fail && measures.includes("m7")) return HttpResponse.json({ error: "X", message: "Series down" }, { status: 500 });
      }),
    );
    const { result } = renderHook(() => useHealthSeries(HEALTH_AGENTS.healthy, MEASURES, { period: "30d" }), { wrapper });

    // Each chunk settles on its own: wait for the failure and for the other chunk's data separately.
    await waitFor(() => expect(result.current.isError).toBe(true));
    await waitFor(() => expect(result.current.byMeasure.has("m1")).toBe(true));
    expect(result.current.error).toBeTruthy();
    expect(result.current.byMeasure.has("m7")).toBe(false);
    expect(calls).toHaveLength(2);

    fail = false;
    act(() => result.current.retry());
    // Wait for each outcome of the retry on its own: m7's data, then the error clearing.
    await waitFor(() => expect(result.current.byMeasure.has("m7")).toBe(true));
    await waitFor(() => expect(result.current.isError).toBe(false));
    // Only the failed call went out again.
    expect(calls).toHaveLength(3);
    expect(calls[2]).toEqual(["m7"]);
  });

  it("is not in error when every call worked", async () => {
    const { result } = renderHook(() => useHealthSeries(HEALTH_AGENTS.healthy, ["agentFailureRate"], { period: "30d" }), { wrapper });
    await waitFor(() => expect(result.current.byMeasure.has("agentFailureRate")).toBe(true));
    expect(result.current.isError).toBe(false);
    expect(result.current.error).toBeNull();
  });
});
