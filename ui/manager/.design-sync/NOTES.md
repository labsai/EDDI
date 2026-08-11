# design-sync notes — EDDI-Manager

EDDI-Manager is an **application**, not a published component library. The synced
"design system" is the presentational surface: `src/components/ui/` + `src/components/shared/`
+ the app chrome from `src/components/layout/` (29 components). Synced via the
**package shape with a dedicated re-export entry**.
Claude Design project: `408b967f-5a31-4a9f-85f7-f8794639218b` ("Design System").

`AppLayout`, `ConfigEditorLayout` and `ThemeProvider` are deliberately **not** synced —
the first two pull Monaco into the bundle, and `ThemeProvider` is a provider rather than a
visual component (it is already used by `ds-providers`). Compose `Sidebar` + `TopBar` by
hand instead of reaching for `AppLayout`.

> **`TopBar` now drags the operator drawer in.** `refactor(operator): move the launcher
> from a floating FAB into both shells' headers` (main, PR #137) moved `OperatorDrawer`
> out of `AppLayout` and into `top-bar.tsx` as a static import. Since `TopBar` is a synced
> component, the bundle grew **4.00 MB → 4.17 MB (+179 KB)** and now contains the operator
> tool-scope allow-list (`tool-scopes`, `WRITE_ENDPOINTS`). Monaco is still out — that was
> always the expensive half — but this is exactly the creep the scoped entry exists to
> prevent. Options if it matters: lazy-load `OperatorDrawer` behind `React.lazy` in
> `TopBar` (keeps both the header launcher and the lean bundle), or drop `TopBar` from the
> synced surface (loses the chrome this sync exists to provide). Measure with the esbuild
> command in "Verifying the surface" below before and after any such change.

## How the build is wired (non-obvious — read before re-syncing)

- **No library `dist/`.** `npm run build` produces an *app* bundle. So `cfg.entry`
  points at `.design-sync/ds-entry.tsx`, a hand-authored file that re-exports exactly
  the 29 scoped components (+ the preview provider). The converter esbuilds from it, so
  only this surface + its deps land in `_ds_bundle.js` — not Monaco/editors/etc.
  **To add/remove a synced component: edit ds-entry.tsx AND `cfg.componentSrcMap`.**
- `componentSrcMap` pins all 29 src paths because there is no shipped `.d.ts`.
- `cfg.tsconfig = tsconfig.app.json` so esbuild resolves the `@/* → ./src/*` alias.
- **Provider chain** `.design-sync/ds-providers.tsx` (`DesignSyncProvider`, used as
  `cfg.provider`): MemoryRouter + QueryClientProvider + ThemeProvider(light) + Radix
  TooltipProvider, and a side-effect `import "@/i18n/config"`. It previews through an
  isolated `i18n.cloneInstance({ lng: "en", detection: { caches: [] } })` so previews are
  deterministic English without mutating the app's shared instance or persisting a locale
  (LanguageDetector otherwise renders labels in the host machine's locale — we caught German).

## Styling = scoped Tailwind v4 compile (`cfg.buildCmd`)

- Tokens live in `@theme` in `src/index.css` (EDDI brand: black & gold, primary `#f59e0b`).
- `cfg.cssEntry = .design-sync/.cache/compiled.css`, produced by `cfg.buildCmd`
  (`node .design-sync/build-css.mjs`) which runs the Tailwind v4 CLI scoped (`source(none)`
  + `@source` for ui/, shared/, **layout/**, ds-entry, previews) over `src/index.css`. This gives a
  **stable, lean (~53 KB)** stylesheet with NO Monaco/VSCode bleed (the whole-app
  `dist/assets/index-*.css` pulled in ~217 `--vscode-*` tokens, Ubuntu Mono/Segoe fonts,
  and codicon — all gone now). `build-css.mjs` needs `@tailwindcss/cli` in
  `.ds-sync/node_modules` (install it alongside the other converter deps).
- **Re-sync MUST run `cfg.buildCmd` first** (the driver does). It regenerates compiled.css.
- Fonts: Noto Sans Variable (Latin + script subsets) wired via
  `cfg.extraFonts = node_modules/@fontsource-variable/noto-sans/index.css`. 9 woff2 ship.

## Preview gotchas (authored in `.design-sync/previews/`)

- **Inline-positioned dialogs** (AccessibleDialog, UnsavedChangesDialog,
  CreateOrWizardDialog) use `position: fixed`, which escapes the preview card. Fix: wrap
  the dialog in `<div style={{position:'relative', transform:'translateZ(0)', height:N, overflow:'hidden'}}>`
  — the `transform` makes `fixed` resolve to that box so the whole dialog (title incl.)
  renders inside the card. AlertDialog uses a radix Portal and needs no wrapper (just
  `cardMode: single` + viewport).
- **CommandPalette ships the floor card on purpose**: it takes no props and only opens
  via a global Ctrl+K store, so it can't render statically. Still fully importable.
- AgentPicker / SecretKeyPicker use `cardMode: column` (they overflow a grid cell).
- Data components (AgentPicker, SecretKeyPicker) fetch via react-query; with no backend
  in preview they show the empty/closed state — previews are authored around that.
- Layout glue in previews is inline styles (not Tailwind classes) so targeted
  preview-rebuilds don't require regenerating compiled.css.
- **`Sidebar` needs a pinned height** — it fills its parent, so the preview wraps it in a
  fixed-height flex frame. It also writes `eddi-sidebar-sections` to localStorage when a
  nav section is collapsed; harmless in preview, but it is a real write.
- **`TopBar`'s breadcrumb derives from the router.** `ds-providers`' `MemoryRouter` starts
  at `/`, so the breadcrumb renders its root state unless the preview supplies
  `initialEntries`.
- `Sidebar` / `TopBar` call `useAuth()`. There is no auth provider in the chain, but
  `AuthContext` has a real `GUEST_CONTEXT` default, so they render the signed-out state
  rather than throwing.

## Known render warns
- None. (Earlier GRID_OVERFLOW on the two pickers resolved via `cardMode: column`;
  earlier FONT_MISSING/TOKENS_MISSING resolved by the scoped cssEntry.)

## Why `layout/` is in the `@source` list (measured, not assumed)

Tailwind v4 **tree-shakes `@theme` tokens**: a token reaches the `:root` block in the
`theme` layer only if some scanned file uses a utility that reads it. A control build
scanning nothing at all emits none of the five `--color-sidebar*` tokens. This is why the
scoped `@source` list is load-bearing for tokens, not just for utilities.

Measured against `src/index.css` (each row is a full CLI build; comment-stripped and
comment-intact runs agree, so nothing here depends on incidental matches):

| `@source` set | `--color-sidebar*` in `:root` |
|---|---|
| nothing | 0 of 5 |
| `ui` + `shared` (pre-patch) | **2 of 5** — `-foreground`, `-accent` |
| `ui` + `shared` + `layout` | **5 of 5** |

The two that survive without `layout` are held up by `shared/mode-switcher.tsx` alone
(`bg-sidebar`, `text-sidebar-foreground`, `text-sidebar-accent`). Everything else that reads
these tokens — `border-sidebar-border`, `fill-sidebar-accent`, `bg-sidebar-accent`,
`text-sidebar-accent-foreground` — lives in `layout/sidebar.tsx`, so scanning `layout` is
what restores them.

Adding the line also emits the layout **utilities** themselves — without it the sheet has no
`.fill-sidebar-accent` and no `.border-s-2`, ~8.7 KB in all (45.4 KB → 54.3 KB), so a synced
`Sidebar`/`TopBar` renders unstyled.

`--color-sidebar-accent-foreground` used to reach `:root` in **no** configuration, because
nothing anywhere used that utility — the sidebar's user avatar paired `bg-sidebar-accent`
with `text-sidebar`. It now uses `text-sidebar-accent-foreground`, its semantically correct
partner (identical in light, one shade off in dark, so no visible change), which is what
takes the count from 4 to 5. **That single call site is the only thing keeping the token in
the bundle** — if it is ever refactored away, the token silently vanishes again.

> An earlier version of this note claimed the pre-patch bundle shipped **zero** sidebar
> tokens and that the `layout` line restores **five**. The first number was wrong (it was 2),
> and five only became true once the avatar call site above was fixed. The conclusion always
> stood: the scan was incomplete and the line is required.

## Verifying the surface without a full sync

Three checks worth running after touching `ds-entry.tsx`, `config.json`, or any synced
component. They catch the things a re-sync would otherwise surface late.

```bash
# 1. Every named export resolves (stronger than checking the file paths exist).
#    Also reports the bundle size, so dependency creep shows up as a number.
node .ds-sync/node_modules/esbuild/bin/esbuild .design-sync/ds-entry.tsx \
  --bundle --format=esm --jsx=automatic --alias:@=./src \
  --external:react --external:react-dom --external:react/jsx-runtime \
  --outfile=/tmp/ds-bundle.js

# 2. Nothing heavy leaked in. All of these must print 0.
grep -oc 'monaco\|vscode\|codicon\|JsonEditor' /tmp/ds-bundle.js

# 3. The tokens actually ship. build-css minifies, so match inside the :root block
#    rather than grepping line-wise.
node .design-sync/build-css.mjs
```

The previews are type-checked by `tsconfig.design-sync.json` (wired into `tsc -b`), so
`npm run typecheck` already catches a preview that passes a prop the component does not
take.

## Re-sync risks / watch-list
- **`build-css.mjs` depends on `src/index.css`'s `@import 'tailwindcss';` line** and on
  `@source`-able component dirs. If the app migrates Tailwind config or moves index.css,
  update build-css.mjs.
- **Two components are in the declared surface but not synced**: `shared/update-check-card.tsx`
  and `layout/update-banner.tsx`, both newer than the last sync. `UpdateCheckCard` pulls
  `react-markdown` + `remark-gfm` (it renders GitHub release notes) and talks to
  `api.github.com`, so syncing it is a real bundle and network decision, not a formality —
  measure with the esbuild command above before adding it.
- **`.design-sync/` is type-checked** by `tsconfig.design-sync.json`, wired into `tsc -b`
  via `tsconfig.json`'s references. It maps `eddi-manager` → `ds-entry.tsx` so the previews
  resolve, and pulls in `src/vite-env.d.ts` for `import.meta.env` / `__APP_VERSION__`. A
  preview that passes a wrong prop now fails `npm run typecheck` instead of surviving until
  someone runs a sync.
- **`cfg.entry` + `componentSrcMap` are hand-maintained.** New ui/shared components are
  NOT auto-discovered — they must be added to both. Conversely, deleting a component from
  the repo without updating these breaks the build.
- Viewport changes in `cfg.overrides` trip `[CONFIG_STALE]` → need a full `package-build`,
  not a targeted preview-rebuild. `cardMode: column` edits did NOT (presentation-only).
- The provider forces English; if the DS should preview other locales, change ds-providers.
- **i18n direction caveat:** ds-providers uses an isolated English i18n instance for
  preview *text*, but `@/i18n/config` still sets `<html dir/lang>` from the *host* locale
  on import. On an LTR-locale build machine (the norm) this is correct; on an RTL-locale
  build machine previews would render LTR English inside an RTL document (mirrored padding/
  icons). If that ever matters, force `document.documentElement.dir = "ltr"` in ds-providers.
- ds-entry / ds-providers import real app code (`@/components/...`, `@/i18n/config`,
  `@/components/layout/theme-provider`) — if those move, update the imports.

## Auth
DesignSync requires design-system authorization (`/design-login` in an interactive
`claude` terminal, or "Send to Claude Code Web"). This first sync was done after the
user authorized interactively.
