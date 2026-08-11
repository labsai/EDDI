# CLAUDE.md — EDDI Manager

Start with **[AGENTS.md](AGENTS.md)**. It owns workflow, branch policy, quality gates, the
i18n mandate, architecture, API conventions and constraints — all of it applies. This file
adds the layer AGENTS.md does not cover: **what UI should be built from and what it should
look like.**

## Load a skill before writing UI

| Task | Skill |
|---|---|
| Anything that renders — a component, a card, a dialog | `.claude/skills/eddi-ui` |
| A whole page — list, detail, config editor, wizard | `.claude/skills/eddi-screens` |
| Data, routes, forms, i18n, tests | `.claude/skills/eddi-data` |

## The five rules that catch most mistakes

1. **Compose, don't recreate.** `src/components/ui/` (11 primitives) and
   `src/components/shared/` (14 shared components) already exist. Import them. Do not
   pull in a fresh shadcn/ui component, and do not hand-roll a button, badge, card,
   dialog, empty state or error state.
2. **Colors come from tokens, never hex.** `bg-primary`, `text-muted-foreground`,
   `border-border`, `bg-card`, `text-destructive`. Tokens are declared in `@theme` in
   `src/index.css` and flip in dark mode. A literal `#f59e0b` in a component is a bug.
3. **Logical properties only.** `ps-*` / `pe-*` / `ms-*` / `me-*` / `start-*` / `end-*` /
   `text-start` / `text-end`. Never `pl-`, `pr-`, `ml-`, `mr-`, `left-`, `right-`. The app
   ships Arabic; `e2e/rtl.spec.ts` will catch you.
4. **Every user-visible string goes through `t("key", "Fallback")`** — then into
   `en.json` and all 10 other locales in the same commit (AGENTS.md §2).
5. **`data-testid` on anything a test asserts on** — rows, buttons, inputs, states.
   Existing naming: `channel-row-${id}`, `create-channel-btn`, `view-toggle-card`.

## Variant props, not restyling

`<Button variant="outline" size="sm">` — not `<Button className="border bg-transparent">`.
Same for `Badge`. If a variant is missing, add it to the `cva` config in the primitive so
the whole app gets it; don't patch it at the call site.

## Design system mirror

The synced surface is **29 components**: 24 of the 25 above plus five pieces of chrome from
`src/components/layout/` (`Sidebar`, `TopBar`, `PlatformStatus`, `PageLoader`,
`MockDataBanner`). `AppLayout` and `ConfigEditorLayout` are excluded on purpose — both pull
Monaco into the bundle. `UpdateCheckCard` and `UpdateBanner` are not synced either (they are
newer than the last sync; see NOTES.md). `.design-sync/conventions.md` is the styling
contract; `.design-sync/NOTES.md` explains the build wiring.

**Adding a component to `ui/`, `shared/` or `layout/` does not add it to the design system**
— it must also be added to `.design-sync/ds-entry.tsx`, `config.json`'s `componentSrcMap`,
and a preview in `.design-sync/previews/`. If it reads a token no other synced file uses,
check that token still reaches `:root` in the compiled CSS: Tailwind v4 tree-shakes `@theme`
tokens, so an unscanned utility means a missing variable (see NOTES.md).
