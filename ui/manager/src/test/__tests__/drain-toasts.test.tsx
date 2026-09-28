import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Toaster, toast } from "sonner";
import { drainToasts } from "../drain-toasts";

const TOAST = "[data-sonner-toast]";

/** Real time passing, long enough for every sonner timer drainToasts should have flushed. */
const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 400));

/**
 * drainToasts() runs before cleanup() in the shared afterEach. Its contract:
 * when it returns, sonner has no timer left that could fire after the Toaster
 * is unmounted — because a timer that fires after the jsdom environment is torn
 * down fails the whole vitest run (see drain-toasts.ts).
 *
 * "Nothing left" is checked by waiting afterwards and looking: a deferred
 * insertion or removal that drainToasts missed shows up as a change in the DOM
 * after it has returned.
 */
describe("drainToasts", () => {
  it("lets a toast created at the last moment land, then removes it", async () => {
    render(<Toaster />);
    // No await in between: sonner has recorded the toast, but its insertion is
    // still a pending setTimeout, so nothing is in the DOM yet.
    toast("created at the end of a test");
    expect(document.querySelector(TOAST)).toBeNull();

    await drainToasts();
    await settle();

    expect(document.querySelector(TOAST)).toBeNull();
    expect(toast.getToasts()).toHaveLength(0);
  });

  it("waits out the removal of a toast already on screen", async () => {
    render(<Toaster duration={600_000} />);
    toast("on screen");
    expect(await screen.findByText("on screen")).toBeInTheDocument();

    await drainToasts();

    // Gone when drainToasts returns, not later: the 200 ms removal timer has
    // already fired, so cleanup() will not leave it pending.
    expect(document.querySelector(TOAST)).toBeNull();
    expect(toast.getToasts()).toHaveLength(0);
  });

  it("clears sonner's store when no Toaster is mounted", async () => {
    toast("no toaster to show me");

    await drainToasts();

    expect(toast.getToasts()).toHaveLength(0);
  });
});
