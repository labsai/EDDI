import { describe, expect, it, vi } from "vitest";
import { clusterAccess } from "@/lib/cluster-access";
import { formatBytes, formatDuration, activityGroup } from "@/lib/cluster-labels";
import { mergeActivity, ACTIVITY_LIMIT, withTimeout } from "@/hooks/use-cluster";
import type { ActivityEvent } from "@/lib/api/cluster";

describe("clusterAccess", () => {
  it("admin may view and act", () => {
    expect(clusterAccess(["eddi-admin"], "keycloak")).toEqual({ canView: true, canAct: true });
  });

  it("viewer may view only — the backend refuses its actions and dead-letter content", () => {
    expect(clusterAccess(["eddi-viewer"], "keycloak")).toEqual({ canView: true, canAct: false });
  });

  it("editor and user may not open the console", () => {
    expect(clusterAccess(["eddi-editor"], "keycloak")).toEqual({ canView: false, canAct: false });
    expect(clusterAccess(["eddi-user", "eddi-approver"], "keycloak")).toEqual({ canView: false, canAct: false });
  });

  it("auth off, or roles from another claim: offer everything and let the backend decide", () => {
    expect(clusterAccess([], "none")).toEqual({ canView: true, canAct: true });
    expect(clusterAccess(["ops-team"], "keycloak")).toEqual({ canView: true, canAct: true });
  });
});

describe("cluster formatting", () => {
  it("formats durations at the scale an on-call admin reads them", () => {
    expect(formatDuration(950)).toBe("950 ms");
    expect(formatDuration(20_000)).toBe("20 s");
    expect(formatDuration(65_000)).toBe("1 min 5 s");
    expect(formatDuration(7_260_000)).toBe("2 h 1 min");
    expect(formatDuration(3 * 86_400_000)).toBe("3 d 0 h");
    expect(formatDuration(-1)).toBe("—");
  });

  it("formats sizes in binary units", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(4_812)).toBe("4.7 KiB");
    expect(formatBytes(610_448)).toBe("596 KiB");
  });

  it("groups activity types for the filter", () => {
    expect(activityGroup("node.lost")).toBe("nodes");
    expect(activityGroup("fence.rejected")).toBe("deadLetters");
    expect(activityGroup("deadletter.created")).toBe("deadLetters");
    expect(activityGroup("admin.node.drain")).toBe("admin");
  });
});

describe("mergeActivity", () => {
  const e = (id: string, ts: number): ActivityEvent => ({ id, type: "node.joined", severity: "info", node: "n1", ts, payload: {} });

  it("keeps each entry once, newest first — the history read after a reconnect overlaps the live ones", () => {
    const merged = mergeActivity([e("a", 1), e("b", 3)], [e("b", 3), e("c", 2)]);
    expect(merged.map((x) => x.id)).toEqual(["b", "c", "a"]);
  });

  it("is capped at the backend's ring size", () => {
    const many = Array.from({ length: ACTIVITY_LIMIT + 20 }, (_, i) => e(`x${i}`, i));
    const merged = mergeActivity([], many);
    expect(merged).toHaveLength(ACTIVITY_LIMIT);
    expect(merged[0]!.id).toBe(`x${ACTIVITY_LIMIT + 19}`);
  });
});

describe("withTimeout", () => {
  it("fails a read that a hung node never answers, so the page shows the failure instead of frozen numbers", async () => {
    vi.useFakeTimers();
    try {
      const hung = withTimeout(() => new Promise<number>(() => {}), 100);
      const result = hung().then(
        () => "resolved",
        (e: Error) => e.message,
      );
      await vi.advanceTimersByTimeAsync(150);
      expect(await result).toMatch(/no answer within 100 ms/);
    } finally {
      vi.useRealTimers();
    }
  });

  it("passes an answer and an error through unchanged", async () => {
    await expect(withTimeout(() => Promise.resolve(7), 100)()).resolves.toBe(7);
    await expect(withTimeout(() => Promise.reject(new Error("409")), 100)()).rejects.toThrow("409");
  });
});
