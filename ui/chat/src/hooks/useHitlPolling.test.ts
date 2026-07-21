import { describe, it, expect, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
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

function mount(onResolved: () => Promise<boolean>, onStatus = vi.fn()) {
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
    const onResolved = vi.fn(async () => true);
    const calls = mockStatuses(["IN_PROGRESS"]);

    mount(onResolved);

    await waitFor(() => expect(calls.count).toBeGreaterThan(0));
    expect(onResolved).not.toHaveBeenCalled();
  });

  it("resolves once a settled state is observed", async () => {
    const onResolved = vi.fn(async () => true);
    mockStatuses(["READY"]);

    mount(onResolved);

    await waitFor(() => expect(onResolved).toHaveBeenCalled());
  });

  it("keeps watching when the post-resolution refresh fails", async () => {
    // A dropped refresh used to stop the loop forever, wedging the widget in a
    // paused state with a locked composer.
    const onResolved = vi.fn(async () => false);
    mockStatuses(["READY"]);

    mount(onResolved);

    await waitFor(() => expect(onResolved).toHaveBeenCalledTimes(1));
    // The loop must have rescheduled rather than returned.
    await waitFor(() => expect(onResolved.mock.calls.length).toBeGreaterThan(1), {
      timeout: 8000,
    });
  }, 15000);

  it("reports a still-paused status to the caller", async () => {
    const onStatus = vi.fn();
    mockStatuses(["AWAITING_HUMAN"]);

    mount(vi.fn(async () => true), onStatus);

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
