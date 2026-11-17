import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { abortableSleep } from "../abortable-sleep";

describe("abortableSleep", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("resolves after the delay", async () => {
    const done = vi.fn();
    void abortableSleep(1_000, new AbortController().signal).then(done);

    await vi.advanceTimersByTimeAsync(999);
    expect(done).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(done).toHaveBeenCalledOnce();
  });

  // A polling loop sleeps on one signal every interval; listeners that outlived
  // their timers piled up on it (about 200 over a five-minute wait).
  it("removes its abort listener once the timer has fired", async () => {
    const signal = new AbortController().signal;
    const add = vi.spyOn(signal, "addEventListener");
    const remove = vi.spyOn(signal, "removeEventListener");

    const sleeping = abortableSleep(10, signal);
    await vi.advanceTimersByTimeAsync(10);
    await sleeping;

    expect(add).toHaveBeenCalledOnce();
    const [, listener] = add.mock.calls[0] ?? [];
    expect(remove).toHaveBeenCalledWith("abort", listener);
  });

  it("rejects with an AbortError and clears its timer when aborted", async () => {
    const controller = new AbortController();
    const sleeping = abortableSleep(10_000, controller.signal);

    controller.abort();

    await expect(sleeping).rejects.toMatchObject({ name: "AbortError" });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("rejects at once on a signal that is already aborted, without starting a timer", async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(abortableSleep(10, controller.signal)).rejects.toMatchObject({ name: "AbortError" });
    expect(vi.getTimerCount()).toBe(0);
  });
});
