## 📐 feat(docs-site): resizable and hideable sidebars (2026-10-27)

**Repo:** EDDI (`feat/docs-site-resizable-sidebars`)

### What changed and why

The docs site's two sidebars — navigation on the left, "On this page" on the right — can now be resized and hidden on desktop, so a reader can give the page the room it needs.

- **Resize:** drag a sidebar's inner edge. The handle is also a keyboard control (`role="separator"`, ← → in 16px steps, Home/End for the limits, double-click to reset), with limits of 200–520px (navigation) and 180–480px (table of contents).
- **Hide:** a button in the header for each side — before the logo for the navigation, before "Website" for the table of contents — with `aria-expanded` and a label that says what it will do.
- **Remembered:** widths and hidden sides are kept per browser in `localStorage` (`eddi-docs-layout`) and applied by an inline script before first paint, so pages do not jump. That script is generated from the same functions the drag code uses ([`sidebar-layout.mjs`](../../docs-site/src/lib/sidebar-layout.mjs)), so its limits cannot drift; a test runs it against a fake document.
- Starlight sizes both sidebars from one `--sl-sidebar-width`; each side now has its own variable, and the right sidebar's fixed box gets an explicit width (Starlight relied on a fixed-width inner container). Drags measure against `documentElement.clientWidth`, not `innerWidth`, so the right pane's edge stays under the pointer when the page has a scrollbar — both found while testing in the browser.
- Desktop only: the navigation from 50rem, the table of contents from 72rem. Phones keep Starlight's own menu and "On this page" dropdown, untouched.
- `docs/mcp-server.md`: the same one-line anchor fix as #1166 (identical bytes, so whichever lands first, the other merges cleanly), so this branch builds against today's `main`.

### Verification

- `npm test` in `docs-site/`: 65 tests, 11 of them new for the layout logic; mutation-checked (swapping the right pane's arrow direction, dropping the clamp from the pre-paint script).
- In a browser at 1440px: dragging each edge (left to 400px, right to 404px with the edge exactly under the pointer), keyboard steps and Home, hiding and showing both sides (content re-centres, handles disappear, labels flip), persistence across reloads with no flash, double-click reset; at 375px nothing new appears and nothing overflows.
