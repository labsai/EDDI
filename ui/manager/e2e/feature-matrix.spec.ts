import { test } from "./fixtures";
import { MATRIX, SURFACES, type CapabilityId, type Cell, type SurfaceId } from "@eddi/app-kit/test/e2e/feature-matrix";

/**
 * The conversation feature matrix (@eddi/app-kit, src/test/e2e/feature-matrix.ts):
 * one test per cell, tagged `@surface:<id>` and `@capability:<id>`. This app
 * runs the Manager's columns (chat-panel, operator); the other columns run in the app that serves them.
 */
const OWN: SurfaceId[] = ["chat-panel","operator"];

for (const surfaceId of OWN) {
  const surface = SURFACES[surfaceId];
  test.describe(`feature matrix: ${surfaceId}`, () => {
    test.beforeEach(async ({ page }) => {
      // German for every cell: the i18n cell asserts it, and the others must
      // not depend on English copy (they assert on test ids).
      await page.addInitScript(() => localStorage.setItem("i18nextLng", "de"));
      await page.goto(surface.path);
      await surface.ready(page);
    });

    for (const [capability, row] of Object.entries(MATRIX) as [CapabilityId, Record<SurfaceId, Cell>][]) {
      const cell = row[surfaceId];
      const title = `${capability} @surface:${surfaceId} @capability:${capability}`;
      if (cell.status === "yes") {
        test(title, async ({ page }) => {
          await cell.check(page);
        });
      } else if (cell.status === "unit") {
        // eslint-disable-next-line no-restricted-syntax -- a matrix cell, not a hidden failure: MSW has no fixture for it and the reason names the unit tests that cover it
        test.skip(
          title,
          { annotation: { type: "unit-tested", description: cell.reason } },
          () => {},
        );
      } else {
        // eslint-disable-next-line no-restricted-syntax -- a matrix cell recording a capability the surface lacks today; it becomes an assertion when a phase adds it
        test.fixme(
          title,
          { annotation: { type: "unsupported", description: cell.reason } },
          () => {},
        );
      }
    }
  });
}
