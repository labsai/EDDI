import { act, waitFor } from "@testing-library/react";
import { toast } from "sonner";
import { vi } from "vitest";

const TOAST = "[data-sonner-toast]";

/** One macrotask, on whichever clock the test is running. */
async function nextMacrotask() {
  await act(async () => {
    if (vi.isFakeTimers()) {
      await vi.advanceTimersByTimeAsync(0);
    } else {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  });
}

/**
 * Let sonner finish every timer it has started while the test's DOM still
 * exists. Awaited by the shared afterEach in setup.ts, before `cleanup()`.
 *
 * sonner (2.0.8) defers all three of its Toaster updates, and cancels none of
 * them on unmount:
 *
 * - a NEW toast reaches the Toaster through `setTimeout(() => flushSync(...))`,
 *   so a toast created in a test's last moments is in sonner's store but not
 *   yet in the DOM;
 * - a dismissal is applied in a `requestAnimationFrame`;
 * - a dismissed toast is then removed by `setTimeout(removeToast, 200)`
 *   (TIME_BEFORE_UNMOUNT).
 *
 * Any of them left pending when `cleanup()` unmounts the Toaster fires later,
 * and in a file's LAST test it can fire after vitest has torn the jsdom
 * environment down. The state update it makes then dies in React on
 * `window is not defined`, which vitest reports as an unhandled error that fails
 * the run with every test green (CI run 36418007466).
 *
 * So: let a pending insertion land, dismiss everything, and wait until sonner
 * has taken the toasts out of the DOM, which happens only after the removal
 * timer has fired. A test that never toasted pays nothing: sonner's store
 * records a toast synchronously on `toast()`, before the deferred insertion,
 * so an empty store and an empty DOM mean nothing is pending. It also clears
 * that module-global store, so one test's toast cannot turn up in the next.
 */
export async function drainToasts() {
  if (!document.querySelector(TOAST) && toast.getToasts().length === 0) {
    return;
  }

  // A toast created at the very end of the test: let its insertion run while
  // the Toaster is still mounted, or it would run after cleanup().
  await nextMacrotask();

  act(() => {
    toast.dismiss();
  });
  if (!document.querySelector(TOAST)) {
    // Toasts in the store with no Toaster mounted to show them: dismissing
    // cleared the store, and there is no DOM, so no timer, to wait for.
    return;
  }

  if (vi.isFakeTimers()) {
    // RTL's waitFor would poll a frozen clock; move it past the rAF and the
    // 200 ms removal delay instead.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
  }
  await waitFor(
    () => {
      if (document.querySelector(TOAST)) {
        throw new Error("a sonner toast is still in the DOM after toast.dismiss()");
      }
    },
    { timeout: 3_000 },
  );
}
