import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * Sidebar navigation.
 *
 * Every test here used to assert `toHaveURL(...)` and nothing else, so a
 * destination that navigated and then threw into the error boundary — or sat on
 * an endless skeleton — passed all seven. A URL is the one thing React Router
 * changes *before* the page has done anything. Each case now also names the
 * heading and the page's own container, which is what "the page rendered"
 * actually means.
 */

/**
 * Destinations reachable from the sidebar, and how to recognise each one.
 *
 * `testId` is deliberately a element the page can only render once its data has
 * arrived — the agent grid, the workflow grid, the conversation grid, the
 * active-conversation list — rather than a page wrapper that renders while the
 * request is still in flight. `resource-types-grid` is the exception: the ten
 * resource types are a static list, so there it means "the page rendered" and
 * nothing more. Every id below was checked against the source.
 */
const DESTINATIONS = [
  {
    label: "Agents",
    link: /agents/i,
    url: /\/manage\/agents/,
    heading: /agent/i,
    testId: "agent-grid",
  },
  {
    label: "Workflows",
    link: /workflows/i,
    url: /\/manage\/workflows/,
    heading: /workflow/i,
    testId: "workflow-grid",
  },
  {
    // Exact match: the sidebar also has an "Active Conversations" link, so a
    // /conversations/i regex resolves to two elements and trips strict mode.
    label: "Conversations",
    link: "Conversations",
    exact: true,
    url: /\/manage\/conversations(?!\/monitoring)/,
    heading: /conversation/i,
    testId: "conversation-grid",
  },
  {
    label: "Active Conversations",
    link: "Active Conversations",
    exact: true,
    url: /\/manage\/conversations\/monitoring/,
    heading: /conversation|monitoring|active/i,
    // Everything else on this page — the list, the refresh button, the bulk bar
    // — is gated behind `ready`, i.e. an agent being selected. On arrival the
    // correct rendered state IS the "Select an agent to monitor" empty state, so
    // that is what this asserts. Anchoring on the list or the refresh control
    // would be asserting a state the page has no business being in yet.
    testId: "empty-state",
    // `empty-state` alone is loose — it would also match a "nothing found"
    // state after a failed load, and two of them on one page would trip strict
    // mode. Pin the copy so this asserts the *right* empty state.
    testText: /select an agent/i,
  },
  {
    label: "Resources",
    link: /resources/i,
    url: /\/manage\/resources/,
    heading: /resource/i,
    testId: "resource-types-grid",
  },
  {
    label: "Chat",
    link: /chat/i,
    url: /\/manage\/chat/,
    heading: /chat/i,
    // chat.tsx carries no testid of its own; the agent selector is the first
    // thing ChatPanel renders and the control the page exists to offer.
    testId: "agent-selector",
  },
] as const;

test.describe("Navigation", () => {
  test("loads dashboard by default", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await expect(page).toHaveURL(/\/manage/);
    // Use heading instead of getByText to avoid matching sidebar "Dashboard"
    await expect(page.locator("h1")).toContainText("Dashboard");
  });

  for (const dest of DESTINATIONS) {
    test(`navigates to ${dest.label} and the page renders`, async ({ page }) => {
      await page.goto("/manage");
      await waitForApp(page);

      const link =
        "exact" in dest && dest.exact
          ? page.getByTestId("sidebar").getByRole("link", { name: dest.link as string, exact: true })
          : page.getByTestId("sidebar").getByRole("link", { name: dest.link });

      await link.click();

      await expect(page).toHaveURL(dest.url);

      // The URL is not the page. Assert the destination actually rendered.
      //
      // Error boundary first, deliberately: if it replaced the page, the
      // visibility assertion below fails too, and whichever runs first is the
      // one that reports. "the error boundary is showing" is the useful
      // message; "agent-grid not found" sends you looking in the wrong place.
      await expect(
        page.getByTestId("error-boundary-fallback"),
        "the destination threw into the error boundary",
      ).toHaveCount(0);
      await expect(page.locator("h1").first()).toContainText(dest.heading);
      await expect(page.getByTestId(dest.testId)).toBeVisible({ timeout: 15_000 });
      if ("testText" in dest) {
        await expect(page.getByTestId(dest.testId)).toContainText(dest.testText);
      }
    });
  }
});
