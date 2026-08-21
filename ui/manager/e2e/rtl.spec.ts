import { test, expect, type Locator, type Page } from "@playwright/test";

test.describe("RTL Layout", () => {
  test("switches to RTL when Arabic is selected", async ({ page }) => {
    await page.goto("/manage");

    // Select Arabic language
    const selector = page.getByTestId("language-selector");
    await selector.selectOption("ar");

    // Verify RTL direction
    const html = page.locator("html");
    await expect(html).toHaveAttribute("dir", "rtl");
    await expect(html).toHaveAttribute("lang", "ar");
  });

  test("switches back to LTR when English is selected", async ({ page }) => {
    await page.goto("/manage");

    // Go to Arabic first
    await page.getByTestId("language-selector").selectOption("ar");
    await expect(page.locator("html")).toHaveAttribute("dir", "rtl");

    // Switch back to English
    await page.getByTestId("language-selector").selectOption("en");
    await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
  });

  test("displays Arabic translations", async ({ page }) => {
    await page.goto("/manage");

    await page.getByTestId("language-selector").selectOption("ar");

    // Check Arabic text is displayed
    await expect(page.getByText("لوحة التحكم").first()).toBeVisible();
  });
});

/**
 * Regression guard for two RTL-only horizontal-overflow bugs:
 *
 * 1. `.skip-to-main` was parked off-screen with `left: -9999px`. That does not
 *    mirror under `dir="rtl"`, and in RTL the left side *is* scrollable
 *    overflow — so every page reported a document `scrollWidth` of
 *    `viewport + 9999` and the whole content area was displaced sideways.
 * 2. The `.cq-card-grid` / `.cq-stat-grid` columns used bare `1fr`, i.e.
 *    `minmax(auto, 1fr)`. That `auto` floor is the item's min-content width,
 *    which for the `truncate` (`white-space: nowrap`) card headings is the
 *    full untruncated string — long Arabic strings pushed the grid past its
 *    container. English happened to be short enough to hide it.
 *
 * Both are layout-level, so they reproduce on any route; `/manage` and
 * `/manage/agents` are checked as representatives.
 */
test.describe("RTL horizontal overflow", () => {
  const DESKTOP = { label: "desktop", width: 1280, height: 900 };

  const VIEWPORTS = [
    { label: "mobile", width: 375, height: 812 },
    { label: "tablet", width: 768, height: 1024 },
    DESKTOP,
  ];

  const ROUTES = ["/manage", "/manage/agents"];

  /**
   * Force Arabic before any app script runs, navigate, and wait for real page
   * content. Gating on the heading rather than just the layout shell matters:
   * these tests assert an *absence* of overflow, so they would pass vacuously
   * against a still-loading skeleton.
   */
  const gotoInArabic = async (
    page: Page,
    route: string,
    viewport: { width: number; height: number },
  ) => {
    await page.setViewportSize(viewport);
    await page.addInitScript(() =>
      window.localStorage.setItem("i18nextLng", "ar"),
    );
    await page.goto(route);

    await expect(page.locator("html")).toHaveAttribute("dir", "rtl");
    await expect(page.getByTestId("app-layout")).toBeVisible();
    await expect(page.locator("main h1")).toBeVisible();
  };

  /** `boundingBox()` is nullable; fail loudly rather than assert-then-`!`. */
  const boundingBoxOf = async (locator: Locator, what: string) => {
    const box = await locator.boundingBox();
    if (!box) throw new Error(`Expected ${what} to have a bounding box`);
    return box;
  };

  for (const vp of VIEWPORTS) {
    for (const route of ROUTES) {
      test(`${route} does not overflow horizontally in Arabic at ${vp.label} (${vp.width}px)`, async ({
        page,
      }) => {
        await gotoInArabic(page, route, vp);

        const metrics = await page.evaluate(() => {
          const de = document.documentElement;
          const main = document.querySelector("main");
          return {
            docScrollWidth: de.scrollWidth,
            docClientWidth: de.clientWidth,
            mainOverflow: main ? main.scrollWidth - main.clientWidth : 0,
          };
        });

        // The document itself must never scroll sideways.
        expect(metrics.docScrollWidth).toBe(metrics.docClientWidth);
        // `<main>` is `overflow-auto` for *vertical* scrolling; any horizontal
        // overflow there is content blowing out of the layout.
        expect(metrics.mainOverflow).toBe(0);
      });
    }
  }

  /**
   * The grid blowout only shows up once a card's text is longer than its
   * column, so asserting against whatever the mock fixtures happen to return
   * is luck. Instead, mount a throwaway grid with deliberately over-long
   * `truncate` text inside the real `@container/main` element and measure it:
   * with `minmax(0, 1fr)` the text ellipsises and the grid fits its container,
   * with a bare `1fr` the columns stretch to the full string.
   */
  for (const gridClass of ["cq-card-grid", "cq-stat-grid"]) {
    test(`.${gridClass} columns truncate rather than overflow their container`, async ({
      page,
    }) => {
      await gotoInArabic(page, "/manage", DESKTOP);

      const probe = await page.evaluate((cls) => {
        // The first child of <main> carries `@container/main`, so the
        // container queries driving these grids resolve exactly as they do in
        // the app. Mounting the probe anywhere else would measure nothing.
        const host = document.querySelector("main > div");
        if (!host) throw new Error("`@container/main` host (main > div) missing");

        // Long enough that a `minmax(auto, 1fr)` column would be many times
        // the container's width, so the assertion cannot pass by luck.
        const overlongHeading = "لوحة التحكم ".repeat(30);
        const grid = document.createElement("div");
        grid.className = cls;
        grid.innerHTML = Array.from(
          { length: 4 },
          () =>
            `<div class="rounded-xl border"><h3 class="truncate">${overlongHeading}</h3></div>`,
        ).join("");

        host.appendChild(grid);
        const measured = {
          scrollWidth: grid.scrollWidth,
          clientWidth: grid.clientWidth,
        };
        grid.remove();
        return measured;
      }, gridClass);

      expect(probe.scrollWidth).toBe(probe.clientWidth);
    });
  }

  test("skip-to-main link stays on-canvas and centres when focused in RTL", async ({
    page,
  }) => {
    await gotoInArabic(page, "/manage", DESKTOP);
    const skip = page.locator(".skip-to-main");

    // Hidden but never parked off-canvas — that is what inflated scrollWidth.
    const hidden = await boundingBoxOf(skip, "the hidden skip link");
    // Not a tautology: `x` is a viewport coordinate, and the bug this test
    // pins (`left: -9999px`) made it large and NEGATIVE. `>= 0` is the whole
    // assertion. The lint rule targets lengths and counts, which cannot be.
    // eslint-disable-next-line no-restricted-syntax
    expect(hidden.x).toBeGreaterThanOrEqual(0);
    expect(hidden.x + hidden.width).toBeLessThanOrEqual(DESKTOP.width);

    // On focus it reveals itself, horizontally centred, still within the
    // viewport and still creating no overflow.
    await skip.focus();
    await expect(skip).toBeVisible();

    const focused = await boundingBoxOf(skip, "the focused skip link");
    expect(focused.x).toBeGreaterThan(0);
    expect(focused.x + focused.width).toBeLessThanOrEqual(DESKTOP.width);
    // Centre of the link sits on the centre of the viewport (±2px rounding).
    const centreOffset = Math.abs(
      focused.x + focused.width / 2 - DESKTOP.width / 2,
    );
    expect(centreOffset).toBeLessThanOrEqual(2);

    const overflow = await page.evaluate(() => {
      const de = document.documentElement;
      return de.scrollWidth - de.clientWidth;
    });
    expect(overflow).toBe(0);
  });
});
