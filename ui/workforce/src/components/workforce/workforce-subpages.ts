/**
 * Literal path segments under `/workforce` that are app pages rather than board
 * ids — i.e. every child of the `/workforce` route in `app.tsx` whose path is not
 * a `:param`.
 *
 * Needed because `/workforce/:boardId` and `/workforce/analytics` are
 * indistinguishable from a path string alone. Anything that reads the second
 * segment as a board id must exclude these, or it will treat a page name as a
 * board and build a URL that matches no route.
 *
 * This duplicates knowledge that lives in the router, so `route-integrity.test.ts`
 * asserts the two stay in sync. Add a `/workforce/<page>` route without updating
 * this set and that test fails rather than the UI breaking quietly.
 *
 * Extracted to its own module (not exported from the component that uses it) to
 * satisfy `react-refresh/only-export-components`, matching how
 * `parser-editor-types.ts` handles the same constraint.
 */
export const WORKFORCE_SUBPAGES: ReadonlySet<string> = new Set([
  "new",
  "analytics",
  "chat",
]);
