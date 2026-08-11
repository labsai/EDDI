---
name: eddi-screens
description: How an EDDI Manager page is actually built — the app shell, the list-page pattern, detail pages, the config-editor chrome, wizards, and loading/empty/error states. Load before creating or restructuring a page.
---

# EDDI Manager screen patterns

Pages live in `src/pages/` and render inside `AppLayout` (sidebar + top bar). The layout
already supplies `p-6`, `max-w-screen-2xl`, scroll, and a `@container/main` context — a
page renders its own content only, no shell, no width cap, no page background.

## Page skeleton

Every page is a single `space-y-6` column (29 of 40 pages). Reference: `src/pages/agents.tsx`.

```tsx
<div className="space-y-6">
  {/* 1. Header: icon + title + subtitle, actions right */}
  <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
    <div>
      <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
        <Bot className="h-8 w-8 text-primary" />
        {t("pages.agents.title")}
      </h1>
      <p className="mt-1 text-muted-foreground">{t("pages.agents.subtitle")}</p>
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
  `p-6` inside `@container/main mx-auto max-w-screen-2xl`. Seven pages
  (`channels`, `channel-detail`, `coordinator`, `orphans`, `schedules`, `secrets`,
  `variables`) still self-pad and end up double-padded — don't copy them.
- `h1` is `flex items-center gap-2 text-3xl font-bold text-foreground` with an
  `h-8 w-8 text-primary` Lucide icon. (`channels.tsx` uses a smaller `text-2xl` /
  `h-6 w-6` variant; it is the outlier, not the rule.)
- The page's primary action is a `primary` Button top-right; secondary actions sit beside it
  in a `flex flex-wrap items-center gap-2` group. Buttons already supply `gap-2` from `cva`.
- Toolbar: search on `flex-1`, `ViewToggle` after it. Persist the choice with
  `getStoredViewMode(page)` / `setStoredViewMode(page, mode)` from
  `src/components/shared/view-mode.ts`. Where a result count is shown, it is a
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
  <ErrorState message={t("common.error")} onRetry={() => refetch()} retryLabel={t("common.retry")} />
)}

{!isLoading && !isError && items.length === 0 && (
  <EmptyState icon={Bot} title={search ? t("common.noResults") : t("agents.empty")}
              description={!search ? t("agents.emptyDescription") : undefined}
              actionLabel={!search ? t("agents.createAgent") : undefined}
              onAction={!search ? () => setCreateOpen(true) : undefined} />
)}
```

Use the `Skeleton` primitive inside a card-shaped wrapper, not a bare `animate-pulse` div.
Always render an error branch — `ErrorState` is in 16 pages, `EmptyState` in 8; the pages
that hand-roll these (`channels.tsx` builds its own empty state inline) are the ones to fix,
not to copy. Empty state distinguishes "no results for this search" from "nothing exists
yet", and only the latter offers the create action.

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
