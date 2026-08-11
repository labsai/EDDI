---
name: eddi-screens
description: How an EDDI Manager page is actually built — the app shell, the list-page pattern, detail pages, the config-editor chrome, wizards, and loading/empty/error states. Load before creating or restructuring a page.
---

# EDDI Manager screen patterns

Most pages live in `src/pages/` and render inside `AppLayout` (sidebar + top bar) via its
`<Outlet />`. The layout already supplies `p-6`, `max-w-screen-2xl`, scroll, and a
`@container/main` context — such a page renders its own content only, no shell, no width cap,
no page background.

**Three surfaces deliberately render outside `AppLayout`** and own their full frame:
`landing-page.tsx` (`/welcome`), `agent-studio.tsx` (`/manage/studio/:agentId`, a full-screen
breakout), and everything under `src/pages/workforce/` (mounted on `WorkforceLayout`, a
standalone app with no Manager chrome). Check `src/app.tsx` before assuming the shell is
there — those pages *do* supply their own padding, and the rules below are about the
`AppLayout` ones.

## Page skeleton

Most pages are a single `space-y-6` column — 29 of the 40 files in `src/pages/`.
Reference: `src/pages/agents.tsx`.

```tsx
<div className="space-y-6">
  {/* 1. Header: icon + title + subtitle, actions right */}
  <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
    <div>
      <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
        <Bot className="h-8 w-8 text-primary" />
        {t("pages.agents.title", "Agents")}
      </h1>
      <p className="mt-1 text-muted-foreground">
        {t("pages.agents.subtitle", "Build and deploy conversational agents")}
      </p>
    </div>
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="outline" data-testid="import-agent-btn">…</Button>
      <Button onClick={() => setCreateOpen(true)} data-testid="create-agent-btn">
        <Plus className="h-4 w-4" />
        {t("createOrWizard.newAgent", "New Agent")}
      </Button>
    </div>
  </div>

  {/* 2. Optional guidance banner */}
  {/* 3. Toolbar: search + ViewToggle */}
  {/* 4. Content: loading → error → empty → card grid or table */}
  {/* 5. Dialogs last */}
</div>
```

Rules that hold across pages:

- **Do not add `p-6` to the page root.** `AppLayout`'s `<main>` already applies
  `p-6` inside `@container/main mx-auto max-w-screen-2xl`, so a page that pads itself
  renders at 48px. Every page currently complies; keep it that way.
- `h1` is `flex items-center gap-2 text-3xl font-bold text-foreground` with an
  `h-8 w-8 text-primary` Lucide icon. **Heading size is genuinely mixed** — 22 `text-3xl`
  against 18 `text-2xl` (plus 3 `text-xl` on dense detail headers). `text-3xl` is the one to
  reach for on a new top-level page, but match the neighbouring screens rather than treating
  either as absolute.
- The page's primary action is a `primary` Button top-right; secondary actions sit beside it
  in a `flex flex-wrap items-center gap-2` group. Buttons already supply `gap-2` from `cva`.
- Toolbar: search on `flex-1`, `ViewToggle` after it. Persist the choice with
  `getStoredViewMode(page)` / `setStoredViewMode(page, mode)` from
  `src/components/shared/view-mode.ts` — all 6 pages with a `ViewToggle` do.
  Where a result count is shown, it is a
  `text-xs font-semibold uppercase tracking-wider text-muted-foreground` line above the grid.
- Inline guidance banners: `rounded-xl border border-primary/20 bg-primary/5 p-4`.

## The three content states, in this order

Loading → error → empty → content, as guarded blocks (the `agents.tsx` form):

```tsx
{isLoading && (
  <div className="cq-card-grid" data-testid="agents-loading">
    {Array.from({ length: 4 }).map((_, i) => (
      <div key={i} className="rounded-xl border border-border bg-card p-5 space-y-3">
        <Skeleton className="h-5 w-3/4" />
        <Skeleton className="h-4 w-1/2" />
      </div>
    ))}
  </div>
)}

{isError && (
  <ErrorState message={t("common.error", "Something went wrong")}
              onRetry={() => refetch()} retryLabel={t("common.retry", "Retry")} />
)}

{!isLoading && !isError && items.length === 0 && (
  <EmptyState icon={Bot}
              title={search ? t("common.noResults", "No results found")
                            : t("agents.empty", "No agents yet")}
              description={!search ? t("agents.emptyDescription", "Use the wizard to create one.") : undefined}
              actionLabel={!search ? t("agents.createAgent", "Create Agent") : undefined}
              onAction={!search ? () => setCreateOpen(true) : undefined} />
)}
```

Use the `Skeleton` primitive inside a card-shaped wrapper, not a bare `animate-pulse` div.
`ErrorState` is in 19 pages, `EmptyState` in 9. Empty state distinguishes "no results for
this search" from "nothing exists yet", and only the latter offers the create action.

**Every page that loads data has an error branch — keep it that way.** Falling through to
the empty state on a failed fetch is the recurring bug here: it tells the user "nothing
exists yet" or "data will appear automatically" when the truth is "we could not reach the
backend". Pick the shape by surface:

- `ErrorState` — replaces the container. For a page or panel with nothing else to show.
  Carries `data-testid="error-state"` and `data-testid="error-state-retry"`; assert on those
  rather than on the translated copy, which changes with the locale.
- `RefetchErrorNotice` — a compact amber strip that keeps the last good data on screen.
  For a *background* refetch failure on a polling page, or an inline control (a picker
  inside a form) where a full error box would be out of scale. Pass an explicit `message`
  when the initial load failed — its default wording says data is merely stale.
- A neutral "unknown" state — when the value drives a decision. `gdpr.tsx` renders a grey
  "status unavailable" chip rather than its green "Processing Active" badge, and disables
  the toggle, because reporting an unknown restriction state as a known-safe one is worse
  than showing nothing.

## Card grid vs table

Card grid: `grid gap-4 sm:grid-cols-2 lg:grid-cols-3`. When the page can sit next to the
open chat drawer, prefer the container-query classes from `src/index.css` — `cq-card-grid`
(1→2→3→4) or `cq-stat-grid` (1→2→4) — which respond to the content area, not the viewport.

Table: wrap in `rounded-xl border border-border/50 overflow-hidden`, header row
`border-b bg-muted/50` with `text-start px-4 py-3 font-medium` cells, body rows
`border-b border-border/30 hover:bg-muted/30 cursor-pointer transition-colors`, whole row
navigates, IDs in `font-mono text-xs text-muted-foreground`, numeric/version columns
`text-end`. Give each row `data-testid={\`thing-row-\${id}\`}`.

## Detail pages

`BackLink` at the top, then the same header block (title + subtitle + actions), then
content in `Card`s. Deletes go through `AlertDialog` with `isPending` bound to the
mutation. Resource routes carry the version: `/manage/channels/:id?version=N`.

## Config editors

Never build editor chrome. `ConfigEditorLayout`
(`src/components/editors/config-editor-layout.tsx`) owns the Form↔JSON tabs, version
picker, compare, dirty indicator, Save / Discard / Save & Test, and the unsaved-changes
guard. A new editor supplies only the form body:

```tsx
<ConfigEditorLayout
  typeName={t("rulesEditor.title", "Behavior Rules")} typeIcon={GitBranch}
  resourceId={id} data={json} versions={versions} currentVersion={version}
  onVersionChange={setVersion} onSave={save} onSaveAndDeploy={saveAndDeploy}
  renderFormEditor={(parsed, onChange, readOnly) => (
    <RulesEditor data={parsed} onChange={onChange} readOnly={readOnly} />
  )}
/>
```

Then register it in `EDITOR_MAP` (`src/components/editors/editor-registry.tsx`), add an MSW
handler, add i18n keys, add a test — the four steps in AGENTS.md §3.

## Wizards

`agent-wizard.tsx` and `group-wizard.tsx` are the reference. Entry is
`CreateOrWizardDialog` (quick create vs. guided). Keep step state local; only commit on
finish.

## Accessibility baked into the patterns

Tab bars and toggles are `role="tablist"`/`radiogroup` with arrow-key handling and roving
`tabIndex` — copy the handler from `ConfigEditorLayout` or `ViewToggle` rather than writing
plain buttons. Icons that repeat a visible label get `aria-hidden="true"`; icon-only
controls get `aria-label` via `t()`. The global focus ring is already defined in
`src/index.css` — don't remove outlines.
