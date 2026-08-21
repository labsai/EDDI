import { test, expect } from "./fixtures";
import { type Page } from "@playwright/test";
import { waitForApp } from "./e2e-helpers";

/**
 * Regression guard: the app shell must never scroll vertically.
 *
 * `AppLayout` is `h-screen overflow-hidden` and only `<main>` scrolls — but
 * `overflow-hidden` clips a descendant only when the clipping element sits
 * between that descendant and its *containing block*. An absolutely positioned
 * element with no positioned ancestor resolves against the initial containing
 * block instead, so every scroll container above it is bypassed: it extends the
 * document's scroll height and, once focused, the browser scrolls the whole
 * layout to reveal it.
 *
 * That is not hypothetical. Tailwind's `sr-only` is `position: absolute`, and
 * the parser editor hides each checkbox's real `<input>` with it inside a label
 * that was not `relative`. Sixteen checkboxes stacked the document to ~1.9× the
 * viewport, and tabbing into the last one dragged the sidebar and top bar
 * off-screen.
 *
 * The vertical counterpart to the horizontal guard in `rtl.spec.ts`.
 */
test.describe("Layout vertical overflow", () => {
  const documentMetrics = (page: Page) =>
    page.evaluate(() => {
      const de = document.documentElement;
      return { scrollHeight: de.scrollHeight, clientHeight: de.clientHeight };
    });

  /**
   * Asserting against whatever the mock fixtures happen to render is luck: a
   * page that fits on screen passes this suite without exercising anything.
   * So mount the offending shape deliberately — an `sr-only` input parked far
   * below the fold inside `<main>` — and measure the shell's response. With
   * `AppLayout` positioned, the probe belongs to the shell and `<main>` clips
   * it; without, it resolves against the initial containing block and drags
   * the document with it.
   */
  test("an absolutely positioned element inside <main> cannot grow the document", async ({
    page,
  }) => {
    await page.goto("/manage");
    await waitForApp(page);

    const result = await page.evaluate(() => {
      const de = document.documentElement;
      const main = document.getElementById("main-content");
      if (!main) throw new Error("Expected #main-content to exist");

      const probe = document.createElement("div");
      // The spacer comes first so the input's *static* position — where an
      // `absolute` box with `top: auto` lands — is unambiguously past the fold
      // on any viewport this suite runs at. An input at the top of the probe
      // would sit on screen and measure nothing.
      const spacer = document.createElement("div");
      spacer.style.height = "3000px";
      probe.appendChild(spacer);
      const input = document.createElement("input");
      input.type = "checkbox";
      input.className = "sr-only";
      input.setAttribute("data-testid", "overflow-probe");
      probe.appendChild(input);
      main.appendChild(probe);

      try {
        const grewDocument = de.scrollHeight > de.clientHeight;
        input.focus();
        return {
          grewDocument,
          scrollYAfterFocus: window.scrollY,
          // Non-vacuity guards. If `sr-only` ever stops being
          // `position: absolute`, or the spacer stops overflowing `<main>`,
          // this test proves nothing and should be rewritten rather than pass
          // silently.
          probeIsAbsolute: getComputedStyle(input).position === "absolute",
          probeOverflowsMain: main.scrollHeight > main.clientHeight,
        };
      } finally {
        probe.remove();
      }
    });

    expect(result.probeIsAbsolute).toBe(true);
    expect(result.probeOverflowsMain).toBe(true);
    expect(result.grewDocument).toBe(false);
    expect(result.scrollYAfterFocus).toBe(0);
  });

  /**
   * Broad smoke coverage over the real screens: the shell is a fixed-height
   * frame on every route, whatever the page inside it renders.
   */
  const ROUTES = [
    "/manage",
    "/manage/agents",
    "/manage/resources/parser/par1",
    "/manage/resources/rules/beh1",
  ];

  for (const route of ROUTES) {
    test(`${route} does not make the document scrollable`, async ({ page }) => {
      await page.goto(route);
      await waitForApp(page);
      await expect(page.getByTestId("app-layout")).toBeVisible();

      const metrics = await documentMetrics(page);
      expect(metrics.scrollHeight).toBe(metrics.clientHeight);
    });
  }
});
