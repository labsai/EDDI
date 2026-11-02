## 🎨 refactor(manager): components move off hard-coded amber, and a guard keeps it that way — white-label colours, part 2 of 2 (2026-10-26)

**Repo:** EDDI (`feat/white-label-3-colour-sweep`)

### What changed and why

Second half of the colour work in the [white-labeling plan](../../planning/white-label-ui-plan.md). Part 1 added the `brand`, `warn` and `ochre` scales and converted the pages; this part converts every component under `ui/manager/src/components`, so no `amber-N` utility is left in the Manager. With no brand set, every pixel is unchanged, because all three scales resolve to the same amber.

- **Classified by meaning, per component:**
  - `brand` (follows the brand): the Workforce synthesis cards, the dashboard's agents card, the wizard choice card and the pinned-response star.
  - `warn` (cautions): HITL pauses and approval surfaces, pending badges, unsaved markers, in-progress and degraded states, vault and secret-rotation cautions, amber callouts, the amber middle of red/amber/green scales.
  - `ochre` (fixed categorical hue): per-type badges, discussion-style palettes, section accents, the avatar palette (kept in sync with `lib/utils.ts`), syntax and number colours, tool chips, the search `<mark>`, the welcome-modal gradient.
- **Guard:** `src/__tests__/no-hardcoded-amber.test.ts` fails on any `amber-N` utility or brand-gold hex (`#f59e0b`, `#fbbf24`, `#d4a843` and their `rgb()` forms) in `ui/manager/src` and the three shells. Its allow-list is empty; the theme file's custom-property declarations are the one place amber is named.
- Tests that asserted `amber` class names now assert the new scale (`badges`, `resource-type-badge`, `pipeline-railroad`).
