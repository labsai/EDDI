# EDDI-Manager design system

React + **Tailwind CSS v4** components from the EDDI-Manager app (black & gold brand).
Every component is imported from the bundle (`window.EDDI.*`) and styled by Tailwind
utility classes that read EDDI's design tokens. Build screens by composing these
components and styling your own layout with the same utility classes + tokens below.

## Styling idiom — Tailwind utilities over CSS variables

Style layout/spacing with Tailwind utility classes; never hand-write CSS or invent a
class system. Colors come from EDDI's tokens (defined as CSS custom properties), used
via the semantic Tailwind color names below — NOT raw hex.

| Token (class suffix) | Meaning |
|---|---|
| `primary` / `primary-foreground` | brand gold (`#f59e0b`) + text on it |
| `secondary` / `secondary-foreground` | muted neutral surface |
| `background` / `foreground` | page bg + body text |
| `card` / `card-foreground` | card surface + text |
| `muted` / `muted-foreground` | subtle surface + secondary text |
| `border`, `input` | hairline borders, field borders |
| `destructive` / `destructive-foreground` | danger red |

Use them as `bg-primary`, `text-primary-foreground`, `text-muted-foreground`,
`border-border`, `bg-card`, `text-destructive`, etc. Common scales also apply:
spacing (`p-5`, `gap-2`), radius
(`rounded-lg`, `rounded-xl`), text (`text-sm`, `font-medium`), flex/grid.
Dark mode: add the `dark` class to a root ancestor — tokens flip automatically.
Many components forward `className` to extend their styling (merged with `cn()`),
but not all (e.g. `BackLink` only takes `to`/`label`) — check each component's
`.d.ts` for whether `className` is in its props.

## Variant props (don't restyle — use the prop)

- **Button** — `variant`: `primary` | `secondary` | `destructive` | `outline` | `ghost` | `link`; `size`: `sm` | `md` | `lg` | `icon`. Put a lucide icon as a child for an icon+label button.
- **Badge** — `variant`: `default` | `secondary` | `success` | `warning` | `destructive` | `outline`.
- **Card** — compose `Card` > `CardHeader` (`CardTitle`, `CardDescription`) + `CardContent` + `CardFooter`.

## Where the truth lives

- `styles.css` (+ its `@import`ed `_ds_bundle.css` and token CSS) — the complete token + utility vocabulary. Read it before styling.
- `components/<group>/<Name>/<Name>.d.ts` — the exact props for each component.
- `components/<group>/<Name>/<Name>.prompt.md` — per-component usage notes.

## Idiomatic example

```tsx
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter, Button, Badge } from "<bundle>";

<Card className="max-w-md">
  <CardHeader>
    <div className="flex items-center justify-between">
      <CardTitle>Customer Support</CardTitle>
      <Badge variant="success">Deployed</Badge>
    </div>
    <CardDescription>Resolves tier-1 tickets and routes escalations.</CardDescription>
  </CardHeader>
  <CardContent className="text-sm text-muted-foreground">1,284 conversations · 96% resolved</CardContent>
  <CardFooter className="gap-2">
    <Button size="sm">Open</Button>
    <Button size="sm" variant="outline">Configure</Button>
  </CardFooter>
</Card>
```
