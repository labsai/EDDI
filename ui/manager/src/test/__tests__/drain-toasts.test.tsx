import { afterEach, beforeEach, describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Toaster, toast } from "sonner";
import { drainToasts, TOASTER } from "../drain-toasts";

const TOAST = "[data-sonner-toast]";

/**
 * The drain's contract is not about what is on screen. It is: when
 * drainToasts() returns, sonner has no callback left that could run after
 * cleanup() unmounts the Toaster. A callback that runs after the jsdom
 * environment is torn down fails the whole vitest run (see drain-toasts.ts).
 *
 * Asserting on the DOM afterwards misses exactly the cases that matter: a
 * toast inserted and then removed a moment later, or a callback for a toast
 * that Toaster never renders. So this counts the callbacks themselves. It
 * wraps setTimeout and requestAnimationFrame, keeps those scheduled from
 * sonner's code (by stack), and drops each one as it runs or is cancelled.
 */
const pending = new Set<string>();
/** Handle returned to sonner → our key, so a cancellation takes the callback off the list. */
const keyByHandle = new Map<unknown, string>();
const originalSetTimeout = globalThis.setTimeout;
const originalClearTimeout = globalThis.clearTimeout;
const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
const originalCancelAnimationFrame = globalThis.cancelAnimationFrame;
let seq = 0;

function fromSonner() {
  return (new Error().stack ?? "").includes("sonner");
}

beforeEach(() => {
  pending.clear();
  keyByHandle.clear();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).setTimeout = (fn: (...a: unknown[]) => void, ms?: number, ...a: unknown[]) => {
    if (!fromSonner()) {
      return originalSetTimeout(fn, ms, ...a);
    }
    const key = `setTimeout(${ms ?? 0}) #${++seq}`;
    pending.add(key);
    const handle = originalSetTimeout(() => {
      pending.delete(key);
      fn(...a);
    }, ms);
    keyByHandle.set(handle, key);
    return handle;
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).clearTimeout = (handle: unknown) => {
    const key = keyByHandle.get(handle);
    if (key) {
      pending.delete(key);
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return originalClearTimeout(handle as any);
  };
  globalThis.requestAnimationFrame = (cb: FrameRequestCallback) => {
    if (!fromSonner()) {
      return originalRequestAnimationFrame(cb);
    }
    const key = `requestAnimationFrame #${++seq}`;
    pending.add(key);
    const handle = originalRequestAnimationFrame((t) => {
      pending.delete(key);
      cb(t);
    });
    keyByHandle.set(handle, key);
    return handle;
  };
  globalThis.cancelAnimationFrame = (handle: number) => {
    const key = keyByHandle.get(handle);
    if (key) {
      pending.delete(key);
    }
    originalCancelAnimationFrame(handle);
  };
});

afterEach(() => {
  globalThis.setTimeout = originalSetTimeout;
  globalThis.clearTimeout = originalClearTimeout;
  globalThis.requestAnimationFrame = originalRequestAnimationFrame;
  globalThis.cancelAnimationFrame = originalCancelAnimationFrame;
});

describe("drainToasts", () => {
  it("recognises a mounted Toaster, even an empty one", () => {
    render(<Toaster />);
    // If a sonner upgrade changes this container, the drain stops seeing
    // Toasters and every case below would leave callbacks behind.
    expect(document.querySelector(TOASTER)).not.toBeNull();
  });

  it("lets a toast created at the last moment land, then removes it", async () => {
    render(<Toaster />);
    // No await in between: sonner has queued the insertion, nothing is in the DOM.
    toast("created at the end of a test");
    expect(document.querySelector(TOAST)).toBeNull();

    await drainToasts();

    expect([...pending]).toEqual([]);
    expect(document.querySelector(TOAST)).toBeNull();
    expect(toast.getToasts()).toHaveLength(0);
  });

  it("flushes the insertion of a toast dismissed before it was ever shown", async () => {
    render(<Toaster />);
    // Created and dismissed in one tick. The store's active list and the DOM
    // are both empty, but the insertion is still queued.
    const id = toast("dismissed before insertion");
    toast.dismiss(id);
    expect(toast.getToasts()).toHaveLength(0);
    expect(document.querySelector(TOAST)).toBeNull();

    await drainToasts();

    expect([...pending]).toEqual([]);
  });

  it("flushes a dismissal a Toaster received for a toast it does not render", async () => {
    render(<Toaster id="main" />);
    // Aimed at another Toaster: this one filters it out, so nothing renders, but
    // its subscription still queues the insertion and, on dismiss, a frame.
    toast("for someone else", { toasterId: "elsewhere" });

    await drainToasts();

    expect([...pending]).toEqual([]);
    expect(document.querySelector(TOAST)).toBeNull();
  });

  it("waits out the removal of a toast already on screen", async () => {
    render(<Toaster duration={600_000} />);
    toast("on screen");
    expect(await screen.findByText("on screen")).toBeInTheDocument();

    await drainToasts();

    // The 200 ms removal timer has already fired, so cleanup() cannot leave it pending.
    expect([...pending]).toEqual([]);
    expect(document.querySelector(TOAST)).toBeNull();
    expect(toast.getToasts()).toHaveLength(0);
  });

  it("clears sonner's store when no Toaster is mounted", async () => {
    toast("no toaster to show me");

    await drainToasts();

    expect([...pending]).toEqual([]);
    expect(toast.getToasts()).toHaveLength(0);
  });
});
