import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * Phase 7 of the frontend unification plan, in the browser: Workforce is
 * installable (T1.11), its keyboard entry points work (T1.10), and the chat
 * says each reply once to a screen reader (T1.12).
 *
 * The production manifest is EDDI's (RestWorkforceResource#manifest, rendered
 * in the brand); the dev server answers with the default brand's, so the shell
 * and the manifest are checked together here and the branding in the Java
 * tests.
 */

test.describe("Workforce, installable and keyboard-first (phase 7)", () => {
  test("the shell links a manifest that starts and stays in /workforce", async ({ page, request }) => {
    await page.goto("/workforce");
    await waitForApp(page);
    const href = await page.locator('link[rel="manifest"]').getAttribute("href");
    expect(href).toBe("/workforce/manifest.webmanifest");
    const viewport = await page.locator('meta[name="viewport"]').getAttribute("content");
    expect(viewport).toContain("viewport-fit=cover");
    expect(viewport).toContain("interactive-widget=resizes-content");

    const manifest = await (await request.get(href!)).json();
    expect(manifest.start_url).toBe("/workforce");
    expect(manifest.scope).toBe("/workforce");
    expect(manifest.display).toBe("standalone");
    expect(manifest.icons?.length).toBeGreaterThan(0);
  });

  test("no service worker is registered (signed-in data is never cached)", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
    // MSW's own worker serves the mock API in this tier; nothing else may register.
    const scopes = await page.evaluate(async () =>
      (await navigator.serviceWorker.getRegistrations()).map((r) => r.active?.scriptURL ?? ""),
    );
    expect(scopes.filter((url) => !url.endsWith("/mockServiceWorker.js"))).toEqual([]);
  });

  test("Ctrl+K opens the quick switcher and Escape closes it", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
    await page.keyboard.press("Control+k");
    await expect(page.getByTestId("quick-switcher")).toBeVisible();
    await expect(page.getByTestId("quick-switcher-input")).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("quick-switcher")).toBeHidden();
  });

  test("the chat transcript is a log that is not itself live; replies are announced by a status region", async ({ page }) => {
    await page.goto("/workforce/chat?agentId=agent1&conversationId=conv1");
    await waitForApp(page);
    await expect(page.getByTestId("chat-input")).toBeEnabled();
    await expect(page.getByRole("log")).toHaveAttribute("aria-live", "off");
    await expect(page.getByTestId("reply-announcer")).toHaveAttribute("aria-live", "polite");
  });
});
