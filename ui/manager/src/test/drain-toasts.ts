import { act, waitFor } from "@testing-library/react";
import { toast } from "sonner";
import { vi } from "vitest";

const TOAST = "[data-sonner-toast]";

/**
 * sonner's <Toaster> itself, rendered whether or not it holds a toast. It
 * carries no data-sonner attribute of its own, so this is the container
 * sonner 2.0.8 renders: a polite live region marked as a top layer. Nothing
 * else in the app renders that combination. drain-toasts.test.tsx fails if a
 * sonner upgrade changes it, because the drain would then stop seeing
 * mounted Toasters.
 */
export const TOASTER = 'section[aria-live="polite"][data-react-aria-top-layer]';

/** One macrotask on whichever clock the test runs: lets a queued insertion land. */
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
 * Whether a Toaster has been added to the document since the last drain. A
 * test may render a Toaster and unmount it itself before afterEach runs; the
 * drain then finds none in the DOM, but the callbacks it scheduled while it
 * was mounted are still pending. A MutationObserver's records keep the added
 * node even after it is removed again, and `takeRecords()` hands over those
 * not yet delivered, so the drain sees every mount of the test.
 */
let toasterSeen = false;

/** Sets `toasterSeen` if any of these DOM additions is, or contains, a Toaster. */
function noteToasters(records: MutationRecord[]) {
  for (const record of records) {
    for (const node of record.addedNodes) {
      if (node instanceof Element && (node.matches(TOASTER) || node.querySelector(TOASTER))) {
        toasterSeen = true;
        return;
      }
    }
  }
}

const toasterWatch = new MutationObserver(noteToasters);
toasterWatch.observe(document.documentElement, { childList: true, subtree: true });

/**
 * Let the callbacks of a Toaster that is already gone run out: the insertion
 * macrotask, a dismissal frame, and the 200 ms removal timer, the longest of
 * the three. They touch only the unmounted Toaster, so they do nothing, but
 * they must do it while the environment still exists.
 */
async function outlastUnmountedToaster() {
  await act(async () => {
    if (vi.isFakeTimers()) {
      await vi.advanceTimersByTimeAsync(1_000);
    } else {
      await new Promise<void>((resolve) => setTimeout(resolve, 250));
    }
  });
}

/** One animation frame: every frame requested before it runs first. */
async function nextFrame() {
  await act(async () => {
    if (vi.isFakeTimers()) {
      await vi.advanceTimersByTimeAsync(50);
    } else {
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    }
  });
}

/**
 * Let sonner finish every callback it has scheduled while the test's DOM still
 * exists. Awaited by the shared afterEach in setup.ts, before `cleanup()`.
 *
 * sonner (2.0.8) defers each of its Toaster's updates and cancels none of them
 * on unmount:
 *
 * - a new toast reaches a Toaster through `setTimeout(() => flushSync(...))`;
 * - a dismissal reaches it through `requestAnimationFrame`, and does so even
 *   for a toast that Toaster filters out (another `toasterId`);
 * - a dismissed toast is removed by `setTimeout(removeToast, 200)`
 *   (TIME_BEFORE_UNMOUNT).
 *
 * Any of them left pending when `cleanup()` unmounts the Toaster fires later.
 * In a file's LAST test it can fire after vitest has torn the jsdom environment
 * down; the state update it makes then dies in React on `window is not
 * defined`, which vitest reports as an unhandled error that fails the run with
 * every test green (CI run 36418007466).
 *
 * All of those callbacks are scheduled by a Toaster, so the gate is whether a
 * Toaster was mounted during the test, not what it currently shows. A toast
 * created and dismissed in the same tick, or one aimed at another Toaster,
 * leaves nothing visible and still has a callback in flight. Three cases:
 *
 * - a Toaster is mounted: flush a macrotask (insertions), dismiss, flush a
 *   frame (dismissals), and wait until no toast is left in the DOM (removals);
 * - a Toaster WAS mounted this test and the test unmounted it itself: nothing
 *   is left to dismiss (its subscription is gone), but the callbacks it
 *   scheduled before unmounting are not cancelled, so let the longest of them,
 *   the 200 ms removal, run out;
 * - no Toaster this test: nothing ever subscribed, so nothing can be pending.
 *
 * In every case sonner's module-global store is cleared, so one test's toast
 * cannot turn up in the next.
 */
export async function drainToasts() {
  noteToasters(toasterWatch.takeRecords());
  const mounted = document.querySelector(TOASTER) !== null;
  const wasMounted = toasterSeen;
  toasterSeen = false;

  if (!mounted) {
    if (wasMounted) {
      await outlastUnmountedToaster();
    }
    if (toast.getToasts().length > 0) {
      toast.dismiss();
    }
    return;
  }

  await nextMacrotask();
  act(() => {
    toast.dismiss();
  });
  await nextFrame();

  if (vi.isFakeTimers()) {
    // RTL's waitFor polls on the test's clock and would never advance a frozen
    // one; move it past the 200 ms removal delay instead.
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
  // The DOM goes before React runs the unmounted toasts' passive-effect
  // cleanups, and one of those clears the auto-dismiss timer that dismissing
  // just restarted. Flush them here, so the drain's contract holds when it
  // returns and not only once cleanup() has flushed them too.
  await act(async () => {});
}
