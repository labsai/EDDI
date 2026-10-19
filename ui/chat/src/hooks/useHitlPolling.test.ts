import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useHitlPolling } from "./useHitlPolling";
import { setBaseUrl } from "@/api/http";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

/** approval-status returns each queued state in turn, repeating the last. */
function mockStatuses(states: string[]) {
  let i = 0;
  const calls = { count: 0 };
  globalThis.fetch = vi.fn(async () => {
    const state = states[Math.min(i, states.length - 1)];
    i += 1;
    calls.count += 1;
    return new Response(
      JSON.stringify({
        conversationId: "c1",
        state,
        pausedAt: "",
        pauseReason: "",
        timeoutPolicy: "",
        approvalTimeout: "",
        pauseDetails: null,
      }),
      { status: 200 },
    );
  }) as typeof fetch;
  return calls;
}

function mount(
  onResolved: (isStale: () => boolean) => Promise<boolean>,
  onStatus = vi.fn(),
) {
  setBaseUrl("");
  return renderHook(() =>
    useHitlPolling({
      conversationId: "c1",
      paused: true,
      onStatus,
      onResolved,
    }),
  );
}

describe("useHitlPolling", () => {
  it("does not treat IN_PROGRESS as resolution", async () => {
    // resume() CASes AWAITING_HUMAN -> IN_PROGRESS BEFORE running the approved
    // turn. Resolving here abandoned the watch mid-flight and the user never
    // saw the answer they had waited for.
    const onResolved = vi.fn(async (_isStale: () => boolean) => true);
    const calls = mockStatuses(["IN_PROGRESS"]);

    mount(onResolved);

    await waitFor(() => expect(calls.count).toBeGreaterThan(0));
    expect(onResolved).not.toHaveBeenCalled();
  });

  describe("with the pause not stored yet (stream announces it before persisting)", () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());
    const advance = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)));

    it("does NOT treat a first READY read as resolution: keeps the card and re-polls fast", async () => {
      const onResolved = vi.fn(async (_isStale: () => boolean) => true);
      const onStatus = vi.fn();
      const calls = mockStatuses(["READY", "READY", "AWAITING_HUMAN"]);
      mount(onResolved, onStatus);

      await advance(0);
      expect(calls.count).toBe(1);
      expect(onResolved).not.toHaveBeenCalled();
      await advance(600);
      expect(calls.count).toBe(2);
      expect(onResolved).not.toHaveBeenCalled();
      await advance(1100);
      expect(calls.count).toBe(3);
      expect(onStatus).toHaveBeenCalledWith(expect.objectContaining({ state: "AWAITING_HUMAN" }));
      expect(onResolved).not.toHaveBeenCalled();
    });

    it("resolves on a settled state once the pause has been seen", async () => {
      const onResolved = vi.fn(async (_isStale: () => boolean) => true);
      mockStatuses(["READY", "AWAITING_HUMAN", "READY"]);
      mount(onResolved);

      await advance(0);
      await advance(600); // AWAITING_HUMAN observed
      expect(onResolved).not.toHaveBeenCalled();
      await advance(6100); // normal (backed-off) cadence from here
      expect(onResolved).toHaveBeenCalledTimes(1);
    });

    it("resolves on READY after the 10 s window when the pause was never seen (decided before the first poll)", async () => {
      const onResolved = vi.fn(async (_isStale: () => boolean) => true);
      mockStatuses(["READY"]);
      mount(onResolved);

      await advance(9_000);
      expect(onResolved).not.toHaveBeenCalled();
      await advance(3_000);
      expect(onResolved).toHaveBeenCalledTimes(1);
    });

    it("treats ENDED / ERROR / EXECUTION_INTERRUPTED as resolution at once", async () => {
      for (const state of ["ENDED", "ERROR", "EXECUTION_INTERRUPTED"]) {
        const onResolved = vi.fn(async (_isStale: () => boolean) => true);
        mockStatuses([state]);
        const { unmount } = mount(onResolved);
        await advance(0);
        expect(onResolved).toHaveBeenCalledTimes(1);
        unmount();
      }
    });

    it("keeps watching when the post-resolution refresh fails", async () => {
      // A dropped refresh used to stop the loop forever, wedging the widget in a
      // paused state with a locked composer.
      const onResolved = vi.fn(async (_isStale: () => boolean) => false);
      mockStatuses(["AWAITING_HUMAN", "READY"]);
      mount(onResolved);

      await advance(0);
      await advance(3_100);
      expect(onResolved).toHaveBeenCalledTimes(1);
      await advance(10_000);
      expect(onResolved.mock.calls.length).toBeGreaterThan(1);
    });
  });

  it("reports a still-paused status to the caller", async () => {
    const onStatus = vi.fn();
    mockStatuses(["AWAITING_HUMAN"]);

    mount(vi.fn(async (_isStale: () => boolean) => true), onStatus);

    await waitFor(() =>
      expect(onStatus).toHaveBeenCalledWith(
        expect.objectContaining({ state: "AWAITING_HUMAN" }),
      ),
    );
  });

  it("does not poll when the conversation is not paused", async () => {
    const calls = mockStatuses(["AWAITING_HUMAN"]);
    setBaseUrl("");

    renderHook(() =>
      useHitlPolling({
        conversationId: "c1",
        paused: false,
        onStatus: vi.fn(),
        onResolved: vi.fn(async () => true),
      }),
    );

    await new Promise((r) => setTimeout(r, 50));
    expect(calls.count).toBe(0);
  });
});

describe("useHitlPolling in a hidden tab", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setVisibility("visible");
  });

  function setVisibility(state: "visible" | "hidden") {
    Object.defineProperty(document, "visibilityState", { value: state, configurable: true });
    document.dispatchEvent(new Event("visibilitychange"));
  }
  const advance = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)));

  it("stops polling while hidden and reads at once when the tab comes back", async () => {
    const calls = mockStatuses(["AWAITING_HUMAN"]);
    const unmount = mount(vi.fn(async () => true)).unmount;
    await advance(0);
    expect(calls.count).toBe(1);

    setVisibility("hidden");
    // The poll that was already scheduled may still run once; after that, nothing.
    await advance(10 * 60_000);
    const whileHidden = calls.count;
    await advance(10 * 60_000);
    expect(calls.count).toBe(whileHidden);

    await act(async () => setVisibility("visible"));
    await advance(0);
    expect(calls.count).toBe(whileHidden + 1);
    unmount();
  });
});
