import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * The Workforce surface had no E2E spec at all.
 *
 * Part of the reason is mechanical: `/workforce/*` renders WorkforceLayout, not
 * AppLayout, so `waitForApp` — which waited on `app-layout` — could not be used
 * on any of these routes. Both shells are recognised now.
 *
 * What it covers is the part that destroys data. Three guards landed with unit
 * tests only: the bulk delete that used to fire on the click, the agent editor
 * that used to discard unsaved edits on two of its four exits, and the template
 * delete that used to ask through `window.confirm`. Unit tests assert those
 * against a mocked module graph; these assert them against the real router, the
 * real query client and a real browser, where a dialog that never mounts or a
 * portal that swallows a click would show up.
 *
 * ## What "nothing was deleted" is asserted through, and two wrong answers
 *
 * The first version counted DELETEs with `page.route`. That does not work in
 * this tier and fails in the dangerous direction: MSW answers from a service
 * worker, so a mocked request never reaches the browser's network stack and the
 * handler never fires. Every negative assertion passed because the counter was
 * empty for the wrong reason.
 *
 * The second version asserted the success toast was absent. Also inert, for two
 * separate reasons: `toHaveCount(0)` returns at its FIRST satisfied poll, and
 * the toast only mounts after the mutation resolves — so the count is 0 at the
 * instant the click returns whether or not a delete fired. Even on a slow path
 * sonner's 4s lifetime expires inside Playwright's 5s retry window. A reviewer
 * regressed Cancel to call onConfirm and it still passed 13 runs of 14.
 *
 * What these assert on now is the bulk toolbar. `handleBulkDelete` ends with
 * `setSelectedIds(new Set())` and `setBulkMode(false)` on every path, success or
 * failure, and the toolbar is mounted only while `bulkMode && selectedIds.size
 * > 0` — so it survives if and only if no delete was attempted. It is a state
 * assertion rather than an event one, so it retries instead of racing, and it
 * fails deterministically the moment Cancel starts deleting.
 */

/** The success toast the bulk delete raises, in the singular. */
const DELETED_TOAST = /Deleted 1 task force$/;

async function selectFirstTaskForce(page: import("@playwright/test").Page) {
  await page.getByTestId("bulk-select-toggle").click();
  const firstCard = page.getByTestId(/^select-board-/).first();
  await expect(firstCard).toBeVisible();
  await firstCard.click();
}

test.describe("Workforce dashboard — bulk delete", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
  });

  test("renders the dashboard shell", async ({ page }) => {
    await expect(page.getByTestId("workforce-layout")).toBeVisible();
    await expect(page.getByTestId("bulk-select-toggle")).toBeVisible();
  });

  test("deletes nothing until the deletion is confirmed", async ({ page }) => {
    await selectFirstTaskForce(page);
    await page.getByTestId("bulk-delete-btn").click();

    // The dialog is up...
    await expect(page.getByRole("dialog")).toBeVisible();
    // ...and the selection behind it is intact, which it would not be if the
    // click had deleted: handleBulkDelete clears bulkMode and unmounts this bar.
    await expect(page.getByTestId("bulk-delete-btn")).toBeVisible();
  });

  test("dismissing the dialog deletes nothing", async ({ page }) => {
    await selectFirstTaskForce(page);
    await page.getByTestId("bulk-delete-btn").click();

    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();

    // Still selectable, so nothing was deleted. This is the assertion that goes
    // red if Cancel is ever wired to the confirm handler.
    await expect(page.getByTestId("bulk-delete-btn")).toBeVisible();
  });

  test("confirming deletes the selected task force", async ({ page }) => {
    await selectFirstTaskForce(page);
    await page.getByTestId("bulk-delete-btn").click();

    await page.getByRole("dialog").getByRole("button", { name: "Delete" }).click();

    await expect(page.getByText(DELETED_TOAST)).toBeVisible({ timeout: 10_000 });
    // The mirror of the two negatives above: the bar is gone once a delete ran.
    await expect(page.getByTestId("bulk-delete-btn")).toBeHidden();
  });

  test("the dialog says how many are at risk, in the singular", async ({ page }) => {
    await selectFirstTaskForce(page);
    await page.getByTestId("bulk-delete-btn").click();

    const dialog = page.getByRole("dialog");
    // One is selected. This read "Dissolve 1 task forces?" until the key was
    // given plural forms.
    await expect(dialog.getByRole("heading")).toHaveText(/Dissolve this task force\?/i);
    await expect(dialog.getByText(/cannot be undone/i)).toBeVisible();
  });
});


/**
 * The template delete used `window.confirm`, which is invisible to jsdom AND
 * to Playwright unless a dialog handler is registered — unhandled, Playwright
 * auto-dismisses it, so the delete silently never happened and any assertion
 * that it had would have failed for a reason nobody would have guessed.
 *
 * Templates live in localStorage, so 'was it deleted' has a definite answer
 * that does not depend on the mock layer at all.
 */
test.describe("Workforce dashboard — template delete", () => {
  const STORAGE_KEY = "workforce-templates";
  const TEMPLATES = [
    {
      id: "tpl-e2e-1",
      name: "Product Review Panel",
      description: "Peer review",
      style: "ROUND_TABLE",
      members: [{ displayName: "Ana", role: "reviewer" }],
      maxRounds: 3,
      createdAt: "1970-01-01T00:00:00.000Z",
    },
    {
      id: "tpl-e2e-2",
      name: "Incident Retro",
      description: "After the fact",
      style: "DEBATE",
      members: [{ displayName: "Bo", role: "lead" }],
      maxRounds: 2,
      createdAt: "1970-01-01T00:00:00.000Z",
    },
  ];

  const stored = (page: import("@playwright/test").Page) =>
    page.evaluate(
      (key) =>
        (JSON.parse(localStorage.getItem(key) ?? "[]") as Array<{ id: string }>).map(
          (t) => t.id,
        ),
      STORAGE_KEY,
    );

  test.beforeEach(async ({ page }) => {
    await page.addInitScript(
      ([key, value]) => localStorage.setItem(key, value),
      [STORAGE_KEY, JSON.stringify(TEMPLATES)] as const,
    );
    await page.goto("/workforce");
    await waitForApp(page);
  });

  test("asks before removing a saved template", async ({ page }) => {
    await page.getByTestId("template-delete-tpl-e2e-1").click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText("Product Review Panel");
    expect(await stored(page)).toEqual(["tpl-e2e-1", "tpl-e2e-2"]);
  });

  test("keeps the template when the dialog is dismissed", async ({ page }) => {
    await page.getByTestId("template-delete-tpl-e2e-1").click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();

    expect(await stored(page)).toEqual(["tpl-e2e-1", "tpl-e2e-2"]);
  });

  test("removes only the confirmed template", async ({ page }) => {
    await page.getByTestId("template-delete-tpl-e2e-1").click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete" }).click();

    await expect.poll(() => stored(page)).toEqual(["tpl-e2e-2"]);
  });

  test("the delete control has an accessible name", async ({ page }) => {
    // Icon-only and lucide marks the glyph aria-hidden, so without an
    // aria-label a screen reader announced nothing at all.
    await expect(
      page.getByRole("button", { name: "Delete template" }).first(),
    ).toBeVisible();
  });
});