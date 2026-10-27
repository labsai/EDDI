## 🎨 refactor(manager): brand, warn and ochre colour scales — white-label colours, part 1 of 2 (2026-10-26)

**Repo:** EDDI (`feat/white-label-2-colour-scales`)

### What changed and why

Next step of the [white-labeling plan](../../planning/white-label-ui-plan.md) after the brand store: the Manager's colours become able to follow a brand. Amber was both the EDDI brand and the app's caution colour, written out as about 745 `amber-N` utilities, so a brand colour could not reach them. This part adds the colour scales and converts the pages, `lib/` and the HTML shells; the components follow in part 2, together with the guard that forbids `amber-N`. With no brand set, every pixel is unchanged.

- **Three scales in `ui/manager/src/index.css`**, in plain `@theme` (not `@theme inline`) so the utilities read the variable at runtime. All three are `var(--color-amber-N)` unbranded:
  - `--color-brand-50…950` follows the brand: accents, selection, primary emphasis.
  - `--color-warn-50…950` is fixed amber for cautions: warnings, approval pauses, pending and degraded states, unsaved-changes markers.
  - `--color-ochre-50…950` is a fixed categorical hue (per-type badges, section accents, avatar and syntax palettes) that is neither a warning nor re-branded, so two categories can never collide with the brand.
- **Pages and `lib/` converted by meaning,** read per component. The welcome modal's `rgba(245,158,11,…)` became `color-mix` over `--color-primary`, and the dark scrollbar greys became `--color-scrollbar-thumb(-hover)`. The shells' loader spinner reads `var(--brand-loader-accent, #d4a843)`.
- **`BrandDelivery`'s `brand.css`**, when a primary colour is set, re-declares the brand scale in `:root` and `.dark` as fixed `color-mix(in oklch, var(--color-primary) N%, white|black)` text and sets `--brand-loader-accent`. It never touches `warn` or `ochre`, and unbranded output stays empty. `BrandDeliveryTest` checks the scale against `index.css`, that `ochre` maps to amber and is never branded, and the shells' loader fallback.
- **Docs:** `docs/white-labeling.md` and the `eddi-ui` skill describe the three scales and when to use each.

### Design decisions

- **Full scales rather than the plan's single `warning` token.** The existing utilities need 50…950 with opacity modifiers; a one-for-one swap keeps the classification reviewable and the output identical.
- **The loader uses its own variable.** The bundle CSS in `<head>` already defines `--color-primary` as `#f59e0b`, so `var(--color-primary, #d4a843)` would have changed the unbranded spinner.
- **Mix ratios** put each brand step near the OKLCH lightness of the amber step it replaces.

### Fixed on the way

`docs/mcp-server.md` linked to an anchor `docs/conversation-ratings.md` does not have, which failed the docs-site build on `main` and on every PR. It now points at the page's "Analyzing and triaging" section, where the rating tools are described.
