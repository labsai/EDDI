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
 * Only possible since the generic descriptors stub stopped shadowing
 * `groupstore`. The dashboard used to render exactly one task force — the
 * eight-group fixture never ran — so selecting two, and therefore the plural
 * wording and the multi-delete loop, could not be exercised in a browser.
 */
test.describe("Workforce dashboard — bulk delete, more than one", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
  });

  test("asks in the plural, naming how many", async ({ page }) => {
    await page.getByTestId("bulk-select-toggle").click();

    const cards = page.getByTestId(/^select-board-/);
    await expect(cards.first()).toBeVisible();
    expect(await cards.count()).toBeGreaterThan(1);

    await cards.nth(0).click();
    await cards.nth(1).click();
    await page.getByTestId("bulk-delete-btn").click();

    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("heading")).toHaveText(
      /Dissolve 2 task forces\?/i
    );
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
/**
 * The board rendering a FINISHED discussion — the state a demo ends in, and the
 * state three separate defects only showed up in.
 *
 * The fixture (`gconv-verdict` in the MSW handlers) carries what a real debate
 * carries and the tidy `gconv1` one does not: a judge's verdict answered as a
 * ```json block, and a member message containing one unbreakable token.
 *
 * ## Why the existing overflow guards could not catch this
 *
 * `rtl.spec.ts` asserts `documentElement.scrollWidth === clientWidth` and
 * `main.scrollWidth === main.clientWidth`. Both pass here no matter how badly
 * the layout breaks, because the app shell clips rather than scrolls — a
 * deliberate choice (6bc077de: "clip turns the failure mode from 'whole page
 * pans' into 'one element is visibly clipped'"). Clipping caps `scrollWidth` at
 * `clientWidth`, so an element parked at x=37,705 registers as *zero* overflow.
 * The guard reported clean while the config panel, the composer's Send button
 * and every per-message action sat thousands of pixels outside the window.
 *
 * So this measures the thing that actually matters — whether elements are still
 * inside the viewport — instead of whether the document scrolls.
 */
test.describe("Workforce board — a finished discussion stays inside the window", () => {
  const BOARD = "/workforce/grp2?version=1&conversation=gconv-verdict";

  /**
   * Elements whose box escapes the viewport, ignoring anything sitting inside a
   * scrollable ancestor.
   *
   * That exemption is the difference between the bug and the cure: a wide code
   * block inside a `<pre overflow-x:auto>` is *supposed* to be wider than the
   * window and scroll within its own box. What must never happen is the box
   * itself — or the panel next to it — leaving the window.
   *
   * It is a WIDE exemption, and knowingly so. `overflow-y: auto` with no
   * explicit `overflow-x` computes `overflow-x: auto` per CSS Overflow, so every
   * vertical scroll box in the app — the transcript included — exempts its whole
   * subtree here. `noSidewaysScroll` below covers what that leaves out; neither
   * check is sufficient alone.
   */
  const escapees = (page: import("@playwright/test").Page) =>
    page.evaluate(() => {
      const out: string[] = [];
      for (const el of Array.from(document.querySelectorAll("*"))) {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) continue;
        if (rect.right <= window.innerWidth + 1 && rect.left >= -1) continue;

        let scrollable = false;
        for (let p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
          const overflowX = getComputedStyle(p).overflowX;
          if (overflowX === "auto" || overflowX === "scroll") {
            scrollable = true;
            break;
          }
        }
        if (scrollable) continue;

        out.push(
          `<${el.tagName.toLowerCase()} class="${(el.getAttribute("class") ?? "").slice(0, 60)}"> right=${Math.round(rect.right)}`,
        );
      }
      return out;
    });

  /**
   * Boxes that CONSTRAIN their width — anything not `overflow-x: visible` —
   * whose content is wider than they are. For everything but a code block that
   * means text stretched its container instead of wrapping.
   *
   * The viewport probe cannot see any of this, and the reason is worth stating:
   * `overflow-y: auto` with no explicit `overflow-x` computes `overflow-x: auto`
   * per CSS Overflow, so the transcript's own scroll box exempts its entire
   * subtree there. A message stretched inside it never leaves the window — it
   * either makes the transcript pan sideways (`auto`) or, worse, is silently
   * amputated (`hidden`/`clip`), with no scrollbar and no way to reach it. A
   * bare unbreakable token — a URL, an id, a stack frame — does exactly that
   * once `min-w-0` has stopped it escaping outright: measured at `scrollWidth`
   * 14,573 inside a 592px card before `break-words` was added.
   *
   * `<pre>` is exempt: a horizontal scrollbar on a code block is the intended
   * containment, not a failure to wrap.
   */
  const clippedOrPanning = (page: import("@playwright/test").Page) =>
    page.evaluate(() => {
      const out: string[] = [];
      for (const el of Array.from(document.querySelectorAll("*"))) {
        if (el.tagName === "PRE" || el.closest("pre")) continue;
        // Visually-hidden content clips into a 1px box on purpose — that is how
        // `sr-only` works, and the skip-to-content link is one.
        if (el.clientWidth <= 1 || el.clientHeight <= 1) continue;
        const style = getComputedStyle(el);
        // `truncate` DECLARES that overflowing is intended and shows an ellipsis
        // for it. Exempting it by declared intent rather than by size matters:
        // this passed locally at 190px and failed on CI at 193px, purely on font
        // metrics, so a size-based tolerance would have been a flake generator.
        if (style.textOverflow === "ellipsis") continue;
        if (style.overflowX === "visible") continue;
        // 4px absorbs sub-pixel rounding and the font-metric differences between
        // a dev machine and CI. It cannot mask what this looks for: the failures
        // are three orders of magnitude larger — 14,573 inside 602.
        if (el.scrollWidth <= el.clientWidth + 4) continue;
        out.push(
          `<${el.tagName.toLowerCase()} class="${(el.getAttribute("class") ?? "").slice(0, 60)}"> ${el.scrollWidth}>${el.clientWidth}`,
        );
      }
      return out;
    });

  for (const vp of [
    { label: "desktop", width: 1280, height: 900 },
    { label: "tablet", width: 768, height: 1024 },
  ]) {
    test(`no element escapes the viewport at ${vp.label} (${vp.width}px)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await page.goto(BOARD);
      await waitForApp(page);
      // Gate on the transcript, not just the shell: this asserts an ABSENCE, so
      // an empty board would pass it vacuously.
      await expect(page.getByTestId("decision-record")).toBeVisible();

      expect(await escapees(page)).toEqual([]);
      expect(await clippedOrPanning(page)).toEqual([]);
    });
  }

  test("the composer and the details panel stay reachable", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto(BOARD);
    await waitForApp(page);
    await expect(page.getByTestId("decision-record")).toBeVisible();

    // `toBeInViewport` is the assertion the old `scrollWidth` probes could not
    // make: it fails on an element that is laid out but parked outside the
    // window, which is exactly how this broke.
    await expect(page.getByTestId("board-send")).toBeInViewport();
    await expect(page.getByTestId("board-config-hide")).toBeInViewport();
    // The accessible names are what a screen-reader user navigates by, so they
    // are asserted too rather than replaced by the ids.
    await expect(page.getByRole("button", { name: "Send" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Hide details panel" })).toBeVisible();
  });

  /**
   * A model writing a long argument separates its sections with `---`, and the
   * typography plugin sizes that rule for a document: 2.857em on both sides,
   * 40px at prose-sm. Margin collapsing does not save it — the rule sits
   * between two blocks that both want space — so each divider cost 80px in a
   * card whose paragraphs are 4px apart, and the message read as three times
   * taller than its content.
   *
   * Asserted as a RATIO rather than a pixel count: the intent is that a divider
   * costs about as much as the heading it introduces, which survives a change
   * to the base font size or to the heading scale. A pixel assertion would just
   * be this rule spelled twice.
   */
  test("a markdown divider does not cost more than the heading it introduces", async ({
    page,
  }) => {
    await page.goto(BOARD);
    await waitForApp(page);
    await expect(page.getByTestId("decision-record")).toBeVisible();

    const spacing = await page.evaluate(() => {
      const rule = document.querySelector(".prose hr");
      if (!rule) return null;
      const heading = rule.nextElementSibling;
      if (!heading || !/^H[1-6]$/.test(heading.tagName)) return null;
      const px = (v: string) => parseFloat(v) || 0;
      return {
        ruleMargin: px(getComputedStyle(rule).marginTop),
        headingMargin: px(getComputedStyle(heading).marginTop),
      };
    });

    expect(spacing, "expected a markdown rule followed by a heading in the fixture").not.toBeNull();
    expect(spacing!.ruleMargin).toBeGreaterThan(0);
    expect(spacing!.ruleMargin).toBeLessThanOrEqual(spacing!.headingMargin);
  });

  test("the judge's verdict reads as prose, never as raw JSON", async ({ page }) => {
    await page.goto(BOARD);
    await waitForApp(page);

    const synthesis = page.getByTestId("synthesis-card");
    await expect(page.getByLabel("Synthesis result")).toBeVisible();
    await expect(synthesis).toContainText("Both sides argued substantively");
    // The winner and the tally are the verdict card's job, directly above it.
    await expect(page.getByTestId("decision-record")).toContainText("Tie");
    // Not `not.toContainText("winner")`: the point is that no JSON *structure*
    // survives to the screen, which is what the raw block put there.
    await expect(synthesis).not.toContainText('"winner"');
    await expect(synthesis).not.toContainText('"scores"');
  });
});

/**
 * The Sessions and Team slide-overs used to be `fixed inset-y-0 end-0`, i.e.
 * full-viewport-height and pinned to the trailing edge — which put them
 * directly on top of the right-hand half of the board's own action bar. Every
 * control there, including the "+ New" button and the Sessions toggle that
 * opened the panel, was unclickable while a panel was open: the first click
 * landed on the panel instead.
 *
 * That is the whole of "the New conversation button doesn't work — sometimes".
 * It worked with no panel open and failed with one, which is exactly what
 * "sometimes" looks like from the outside.
 */
test.describe("Workforce board — slide-overs must not cover the action bar", () => {
  const BOARD = "/workforce/grp2?version=1&conversation=gconv-verdict";

  test.beforeEach(async ({ page }) => {
    await page.goto(BOARD);
    await waitForApp(page);
    await page.getByTestId("sessions-toggle").click();
    await expect(page.getByTestId("sessions-panel")).toBeVisible();
    await expect(page.getByRole("dialog", { name: "Sessions panel" })).toBeVisible();
  });

  test("the panel starts below the action bar", async ({ page }) => {
    const bar = await page.getByTestId("new-discussion-btn").boundingBox();
    const panel = await page.getByTestId("sessions-panel").boundingBox();
    if (!bar || !panel) throw new Error("expected both the New button and the panel to be laid out");

    // Not an overlap check on the whole bar — the panel is allowed to sit
    // beside the transcript. What it must clear is the row the controls are in.
    expect(panel.y).toBeGreaterThanOrEqual(bar.y + bar.height);
  });

  test("+ New still starts a new discussion with the panel open", async ({ page }) => {
    // A plain `.click()` would pass even with the panel on top, because
    // Playwright scrolls and force-hits the element it was given. Asserting on
    // what is actually at those coordinates is what reproduces a covered click.
    const topmost = await page.getByTestId("new-discussion-btn").evaluate((btn) => {
      const rect = btn.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
      return hit ? btn.contains(hit) : false;
    });
    expect(topmost, "the + New button was covered by the Sessions panel").toBe(true);

    await page.getByTestId("new-discussion-btn").click();

    // The selection is URL-backed, so a started-fresh board is one with no
    // `conversation` param and the idle placeholder on screen.
    await expect(page).toHaveURL(/\?version=1$/);
    await expect(page.getByTestId("sessions-panel")).toBeHidden();
    await expect(page.getByTestId("decision-record")).toHaveCount(0);
  });
});
