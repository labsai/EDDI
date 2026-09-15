import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createReconnectScheduler, reconnectDelayMs } from "../sse-reconnect";
import {
  SSE_RECONNECT_BASE_MS,
  SSE_RECONNECT_MAX_ATTEMPTS,
  SSE_RECONNECT_MAX_DELAY_MS,
} from "../constants";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("reconnectDelayMs", () => {
  it("doubles from the base delay", () => {
    expect(reconnectDelayMs(0)).toBe(SSE_RECONNECT_BASE_MS);
    expect(reconnectDelayMs(1)).toBe(SSE_RECONNECT_BASE_MS * 2);
    expect(reconnectDelayMs(2)).toBe(SSE_RECONNECT_BASE_MS * 4);
  });

  it("caps at the maximum delay rather than growing without bound", () => {
    expect(reconnectDelayMs(50)).toBe(SSE_RECONNECT_MAX_DELAY_MS);
    expect(reconnectDelayMs(1000)).toBe(SSE_RECONNECT_MAX_DELAY_MS);
  });

  it("never exceeds the cap at any attempt", () => {
    for (let i = 0; i <= SSE_RECONNECT_MAX_ATTEMPTS; i++) {
      expect(reconnectDelayMs(i)).toBeLessThanOrEqual(SSE_RECONNECT_MAX_DELAY_MS);
    }
  });
});

describe("createReconnectScheduler", () => {
  it("calls connect after the backoff delay, not before", () => {
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    expect(scheduler.schedule()).toBe(true);
    vi.advanceTimersByTime(SSE_RECONNECT_BASE_MS - 1);
    expect(connect).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(connect).toHaveBeenCalledTimes(1);
  });

  it("backs off further on each successive failure", () => {
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    scheduler.schedule();
    vi.advanceTimersByTime(SSE_RECONNECT_BASE_MS);
    expect(connect).toHaveBeenCalledTimes(1);

    // Second attempt must wait twice as long — the old code retried at a flat 5s
    // forever.
    scheduler.schedule();
    vi.advanceTimersByTime(SSE_RECONNECT_BASE_MS);
    expect(connect).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(SSE_RECONNECT_BASE_MS);
    expect(connect).toHaveBeenCalledTimes(2);
  });

  it("gives up after the attempt budget instead of retrying forever", () => {
    // The core regression: a stream the backend will never serve (403 on
    // /administration/logs without eddi-admin) used to be re-requested every 5s
    // for the entire session, starting at app boot.
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    for (let i = 0; i < SSE_RECONNECT_MAX_ATTEMPTS; i++) {
      expect(scheduler.schedule(), `attempt ${i}`).toBe(true);
      vi.advanceTimersByTime(SSE_RECONNECT_MAX_DELAY_MS);
    }

    expect(scheduler.schedule()).toBe(false);
    expect(scheduler.attempts).toBe(SSE_RECONNECT_MAX_ATTEMPTS);

    // Nothing further is queued, however long we wait.
    const callsAtGiveUp = connect.mock.calls.length;
    vi.advanceTimersByTime(SSE_RECONNECT_MAX_DELAY_MS * 100);
    expect(connect).toHaveBeenCalledTimes(callsAtGiveUp);
  });

  it("refills the budget on reset, so an occasional blip never exhausts it", () => {
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    for (let i = 0; i < SSE_RECONNECT_MAX_ATTEMPTS; i++) {
      scheduler.schedule();
      vi.advanceTimersByTime(SSE_RECONNECT_MAX_DELAY_MS);
    }
    expect(scheduler.schedule()).toBe(false);

    scheduler.reset();

    expect(scheduler.attempts).toBe(0);
    expect(scheduler.schedule()).toBe(true);
    // …and the delay is back to the base, not still at the cap.
    vi.advanceTimersByTime(SSE_RECONNECT_BASE_MS);
    expect(connect).toHaveBeenCalledTimes(SSE_RECONNECT_MAX_ATTEMPTS + 1);
  });

  it("cancel() stops a queued attempt", () => {
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    scheduler.schedule();
    scheduler.cancel();
    vi.advanceTimersByTime(SSE_RECONNECT_MAX_DELAY_MS * 10);

    expect(connect).not.toHaveBeenCalled();
  });

  it("keeps only one attempt queued if schedule() is called twice", () => {
    const connect = vi.fn();
    const scheduler = createReconnectScheduler(connect);

    scheduler.schedule();
    scheduler.schedule();
    vi.advanceTimersByTime(SSE_RECONNECT_MAX_DELAY_MS * 2);

    expect(connect).toHaveBeenCalledTimes(1);
  });
});
