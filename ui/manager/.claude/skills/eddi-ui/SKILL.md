---
name: eddi-ui
description: EDDI Manager's UI vocabulary — the 24 existing components, their variant props, the brand tokens, and the styling rules. Load before writing or editing any component that renders.
---

# EDDI Manager UI

Black & gold admin dashboard. React 19 + Tailwind CSS v4 + CSS-variable tokens.
Style with Tailwind utilities that read tokens. Never hand-write CSS, never invent a
class system, never use raw hex.

## What already exists — import it

### `src/components/ui/` — primitives

| Component | Notes |
|---|---|
| `Button` | `variant`: primary (default) · secondary · destructive · outline · ghost · link. `size`: sm · md (default) · lg · icon. `asChild` for link buttons. Lucide icon as a child auto-sizes to 16px. |
| `Badge` | `variant`: default (gold) · secondary · success · warning · destructive · outline. Pill, `text-xs font-semibold`. |
| `Card` | Compose `Card` > `CardHeader` (`CardTitle`, `CardDescription`) + `CardContent` + `CardFooter`. `rounded-xl border bg-card shadow-sm`; header is `p-5 pb-0`, content `p-5`. |
| `Input` | `h-10 rounded-lg`, gold focus ring. Plain `InputHTMLAttributes`. |
| `Skeleton` | Loading placeholder. |
| `AccessibleDialog` | Focus-trapped modal. |
| `AlertDialog` | Confirm/destructive prompt: `open`, `onOpenChange`, `title`, `description`, `onConfirm`, `confirmLabel`, `cancelLabel`, `isPending`, `variant` (`destructive` \| `warning`), plus `children` for extra controls between description and buttons (e.g. a "permanently delete" checkbox). Use for every delete. |
| `UnsavedChangesDialog` | Discard-changes confirm. Pair with `useUnsavedChangesGuard`. |
| `DropdownMenu` | Radix wrapper. |
| `ErrorBoundary` | Wrap risky subtrees. |
| `StreamBadge` | Live/streaming indicator. |

### `src/components/shared/` — app-level

| Component | Use it for |
|---|---|
| `EmptyState` | `icon` (Lucide), `title`, `description?`, `actionLabel?`, `onAction?`. Dashed-border box, `py-16`. |
| `ErrorState` | `message`, `onRetry?`, `retryLabel?`. Destructive-tinted box. |
| `ViewToggle` | Card ↔ list switch. Already keyboard-accessible (arrow keys, radiogroup). |
| `BackLink` | Detail-page back nav. Takes only `to` / `label` — no `className`. |
| `AgentPicker`, `SecretKeyPicker` | Async pickers, already wired to react-query. |
| `ResourceTypeBadge` | `type` slug → per-type color chip (rules amber, apicalls green, llm pink, …). |
| `ActionBadge` | Diff actions: CREATE · UPDATE · SKIP · CONFLICT. |
| `InfiniteScrollSentinel` | Intersection-observer load-more trigger. |
| `CommandPalette` | Global Ctrl+K. Opens via its store, not props. |
| `CreateOrWizardDialog` | "Quick create or launch the wizard" fork. |
| `ModeSwitcher`, `RefetchErrorNotice` | Mode switch; background-refetch failure notice. |

### `src/components/layout/` — the shell

| Component | Notes |
|---|---|
| `AppLayout` | Owns the whole frame: sidebar, top bar, drawers, onboarding, and `<main>` with `p-6`, `max-w-screen-2xl` and an `@container/main` context. A page renders its body only. |
| `Sidebar` | `collapsed` / `onToggle`. Four collapsible nav sections (persisted to `eddi-sidebar-sections`), Manager/Workforce switch, approvals count badge, external links, help menu, version footer. Uses the `sidebar-*` tokens, not the page tokens. |
| `TopBar` | `onMenuClick` / `sidebarVisible`. Breadcrumb, command-palette trigger, theme and chat controls. |
| `PlatformStatus` | Backend connectivity pill with a click-to-expand popover (instance, latency, last checked). |
| `PageLoader` | Route-level skeleton. Use it for lazy-route fallbacks, not for in-page loading. |
| `MockDataBanner` | Demo-mode strip; self-hides unless MSW is active. |
| `ThemeProvider` | Light/dark; toggles the `dark` class. Wrap tests and previews in it. |

## Tokens

Declared in `@theme` in `src/index.css`; `.dark` overrides them. Use the semantic name.

| Class suffix | Light | Meaning |
|---|---|---|
| `primary` / `primary-foreground` | `#f59e0b` / `#0c0a09` | brand gold + text on gold |
| `background` / `foreground` | `#fafaf9` / `#1c1917` | page + body text |
| `card` / `card-foreground` | `#ffffff` / `#1c1917` | card surface |
| `secondary` / `secondary-foreground` | `#f5f5f4` / `#1c1917` | muted surface, hover fills |
| `muted` / `muted-foreground` | `#f5f5f4` / `#78716c` | subtle surface, secondary text |
| `border`, `input` | `#e7e5e4` | hairlines, field borders |
| `destructive` / `destructive-foreground` | `#dc2626` / `#fff` | danger |
| `accent` | `#fbbf24` | brighter gold |
| `sidebar` / `sidebar-foreground` | `#ffffff` / `#44403c` | sidebar surface + text |
| `sidebar-border` | `#e7e5e4` | sidebar hairlines |
| `sidebar-accent` / `-foreground` | `#b45309` / `#ffffff` | active nav; dark mode restores bright gold `#f59e0b` on `#0c0a09` |

Other constants: `--radius: 0.5rem` (`rounded-lg` fields/buttons, `rounded-xl` cards and
containers, `rounded-full` badges), `--font-sans` is Noto Sans Variable with per-script
fallbacks — do not set another font.

Status colors outside the token set (emerald for success/deploy, amber for dirty state,
blue for update) exist in a few places. Reuse the existing pattern rather than inventing
a new palette: `text-emerald-600 dark:text-emerald-400`, `bg-amber-100 … dark:bg-amber-900/30`.

## Examples

Card with status:

```tsx
<Card className="max-w-md">
  <CardHeader>
    <div className="flex items-center justify-between">
      <CardTitle>{agent.name}</CardTitle>
      <Badge variant="success">{t("agents.deployed", "Deployed")}</Badge>
    </div>
    <CardDescription>{agent.description}</CardDescription>
  </CardHeader>
  <CardContent className="text-sm text-muted-foreground">…</CardContent>
  <CardFooter className="gap-2">
    <Button size="sm">{t("common.open", "Open")}</Button>
    <Button size="sm" variant="outline">{t("common.configure", "Configure")}</Button>
  </CardFooter>
</Card>
```

Search field (note `ps-9` and `start-3`, not `pl-9`/`left-3`):

```tsx
<div className="relative flex-1 max-w-sm">
  <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
  <Input className="ps-9" placeholder={t("x.search", "Search…")} value={q}
         onChange={(e) => setQ(e.target.value)} data-testid="x-search" />
</div>
```

## Do not

- **Do not add a shadcn/ui component** because it "looks the same". If the primitive is
  missing, write it into `src/components/ui/` in the house style (`cva` + `cn()`, tokens,
  `forwardRef`, `displayName`) so it is reusable and syncable.
- **Do not use raw hex or Tailwind's default palette for brand color** — `bg-amber-500` is
  not `bg-primary`. (Third-party brand marks like Slack's `#4A154B` are the exception.)
- **Do not restyle a variant at the call site.** Add the variant to the `cva` config.
- **Do not use directional spacing** (`pl-`, `pr-`, `ml-`, `mr-`, `left-`, `right-`,
  `text-left`, `text-right`).
- **Do not write a `.css` file** for component styling. `src/index.css` holds tokens, base
  styles and a few genuinely global patterns (spotlight, container-query grids) — that is
  the only place raw CSS belongs.
- **Do not hardcode a font-family, shadow scale, or radius** outside the tokens.
- **Do not mix component and utility exports in one file** — `react-refresh/only-export-components`
  fails the build.
- **Do not ship an untranslated string.**
