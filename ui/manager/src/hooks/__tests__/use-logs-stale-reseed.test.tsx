import { describe, it, expect, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import type { LogEntry } from "@/lib/api/logs";

// A fake event source per filter, and a reseed fetch the test settles by hand.
const h = vi.hoisted(() => ({
  sources: [] as Array<{ onopen?: () => void; close: () => void }>,
  reseeds: [] as Array<{ level?: string; resolve: (rows: unknown[]) => void }>,
}));

vi.mock("@/lib/api/logs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/logs")>();
  return {
    ...actual,
    createLogEventSource: () => {
      const es = { addEventListener: vi.fn(), close: vi.fn() } as unknown as {
        onopen?: () => void;
        close: () => void;
      };
      h.sources.push(es);
      return es;
    },
    getRecentLogs: (filters: { level?: string }) =>
      new Promise((resolve) => h.reseeds.push({ level: filters.level, resolve })),
  };
});

import { useLogStream } from "@/hooks/use-logs";

const row = (message: string, level: string): LogEntry =>
  ({ level, message, loggerName: "test", timestamp: Date.now() }) as LogEntry;

/**
 * The reseed on (re)connect is a round trip. A reseed started for the old
 * filter that resolved after the filter changed merged the old filter's rows
 * into the new view, where they matched nothing in the filter bar.
 */
describe("useLogStream — a stale reseed is discarded", () => {
  it("drops the old filter's history when it resolves after the change", async () => {
    const { result, rerender } = renderHook(({ level }) => useLogStream({ level }), {
      initialProps: { level: "WARN" },
    });
    act(() => h.sources[0]!.onopen?.());
    expect(h.reseeds.map((r) => r.level)).toEqual(["WARN"]);

    rerender({ level: "ERROR" });
    expect(h.sources).toHaveLength(2);

    await act(async () => h.reseeds[0]!.resolve([row("old warn line", "WARN")]));
    expect(result.current.entries).toEqual([]);

    act(() => h.sources[1]!.onopen?.());
    await act(async () => h.reseeds[1]!.resolve([row("new error line", "ERROR")]));
    expect(result.current.entries.map((e) => e.message)).toEqual(["new error line"]);
  });
});
