import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { LandingPage } from "@/pages/landing-page";

/**
 * `/welcome` is the app's default entry — `/` redirects here and the router's
 * catch-all lands here too — and it had no tests at all. It also owns the
 * remembered-workspace redirect, where the writer stored "manager" while this
 * reader only accepted "manage", so choosing Manager never stuck.
 */

const PREF_KEY = "eddi-landing-preference";

function render() {
  return renderPage("/welcome", <LandingPage />, "/welcome");
}

beforeEach(() => {
  localStorage.clear();
});

describe("LandingPage", () => {
  it("shows both workspace choices when no preference is stored", () => {
    render();
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();

    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("/manage");
    expect(hrefs).toContain("/workforce");
  });

  it("stores 'manage' — the value this page accepts — when Manager is chosen", () => {
    // The exact mismatch that broke the feature: the mode switcher wrote
    // "manager", which getStoredPreference never matched, so the chooser
    // reappeared on every visit.
    render();

    const manager = screen.getAllByRole("link").find((a) => a.getAttribute("href") === "/manage")!;
    fireEvent.click(manager);

    expect(localStorage.getItem(PREF_KEY)).toBe("manage");
  });

  it("stores 'workforce' when Workforce is chosen", () => {
    render();

    const workforce = screen.getAllByRole("link").find((a) => a.getAttribute("href") === "/workforce")!;
    fireEvent.click(workforce);

    expect(localStorage.getItem(PREF_KEY)).toBe("workforce");
  });

  it("redirects instead of rendering the chooser once a preference exists", () => {
    for (const pref of ["manage", "workforce"]) {
      localStorage.setItem(PREF_KEY, pref);
      const { unmount } = render();
      // Redirected, so the chooser heading is gone.
      expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
      unmount();
    }
  });

  it("still redirects for the legacy 'manager' value written by older builds", () => {
    // Kept working deliberately: users who clicked Manager before the fix have
    // "manager" persisted, and must not be sent back to the chooser forever.
    localStorage.setItem(PREF_KEY, "manager");
    render();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
  });

  it("ignores an unrecognised stored value and shows the chooser", () => {
    localStorage.setItem(PREF_KEY, "something-else");
    render();
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("survives localStorage throwing on the preference key", () => {
    // Private-mode browsers throw on access. Scoped to this one key so the throw
    // exercises the page's own guard rather than the test harness's providers,
    // which read localStorage for the theme.
    const getItem = Storage.prototype.getItem;
    const spy = vi
      .spyOn(Storage.prototype, "getItem")
      .mockImplementation(function (this: Storage, key: string) {
        if (key === PREF_KEY) throw new Error("denied");
        return getItem.call(this, key);
      });
    try {
      render();
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    } finally {
      spy.mockRestore();
    }
  });
});
