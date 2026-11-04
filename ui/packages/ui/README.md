# @eddi/ui

The UI foundations EDDI's apps share: design tokens, the theme provider, the
i18next setup, per-locale fonts and the primitives both apps need. React is
allowed here; API calls are not. Consumed **as source** through the npm workspace
link (`exports` point at `src/`), so there is no build step.

Phase 3a of the frontend unification plan (`planning/frontend-unification-plan.md`,
§3.1 and §3.3). The conversation components that will build on it are
`@eddi/conversation`, phase 3b.

| Entry | What it holds | Used by |
| --- | --- | --- |
| `@eddi/ui/theme.css` | The Tailwind v4 `@theme`: semantic colours (`--color-background`, `--color-primary`, …), the `brand-*` / `warn-*` / `ochre-*` scales, radius, font stack, light by default and dark under `.dark`. Moved verbatim from the Manager's `index.css` | the Manager (`@import "@eddi/ui/theme.css"` after `tailwindcss`) |
| `CHAT_TOKEN_ALIASES`, `SEMANTIC_TOKENS` (`chat-aliases.ts`) | Which `--chat-*` name of the Chat UI is an alias of which shared token, and the token names a non-Tailwind app may give values | the Chat UI's `variables.css` and its test |
| `ThemeProvider`, `useTheme`, `applyTheme`, `readStoredTheme`, … (`theme/`) | Dark / light / system: the provider for a React tree and the helpers under it. The root element is marked with a class (Tailwind's `dark` variant) or with `data-theme` | the Manager (provider, class); the Chat UI's `useTheme` (helpers, `data-theme`) |
| `setupI18n`, `setDefaultVariables`, `SUPPORTED_LANGUAGES`, `isRtlLanguage`, `matchLanguage` (`i18n/`) | One i18next setup: English bundled, each other locale its own chunk and only the active one loaded, region tags matched (`de-AT` → `de`), `{{productName}}` default variables, `dir`/`lang` on `<html>` from the resolved language | both apps |
| `@eddi/ui/fonts` (`i18n/locale-fonts.ts`) | `installLocaleFonts`: the Noto family a script needs (Arabic, Thai, Devanagari, JP, KR, SC, TC), loaded when its locale is active | the Manager |
| `Button`, `buttonVariants`, `ThemedToaster`, `cn` (`primitives/`, `lib/`) | The Manager's button (every variant, `size="icon"` for icon buttons) and its theme-following, direction-aware sonner toaster | the Manager (the Chat UI gets them with Tailwind in 3b) |

## Using it

```ts
// i18n: one call; the app keeps its locale files and loader map.
import i18n from "i18next";
import { setupI18n, SUPPORTED_LANGUAGES } from "@eddi/ui";
export const i18nReady = setupI18n({
  i18n,
  languages: SUPPORTED_LANGUAGES,
  english: en,
  loaders: { de: () => import("./locales/de.json"), /* … */ },
  plugins: [LanguageDetector, initReactI18next],
  defaultVariables: { productName, shortName },
});

// Fonts, opted into separately (a bundler emits every family's chunk for any
// app that imports the entry):
import { installLocaleFonts } from "@eddi/ui/fonts";
await installLocaleFonts(i18n);
```

```css
@import "tailwindcss";
@import "@eddi/ui/theme.css";
```

## Rules

- **No API calls, no app import, no `window.__EDDI_*__`.** What the server says
  (the brand, the language list an operator allows) reaches the package as an
  option or a prop. `eslint.config.js` (`sharedPackage({ react: true, noApi: true })`
  in `ui/eslint.config.base.js`) enforces it; `core/src/boundaries.test.ts` checks it
  again. `@eddi/core` never imports this package.
- **No i18n keys of its own yet.** A primitive takes its text as a prop. A `t()`
  call here would be checked against the Manager's locale files by
  `npm run i18n:check` (which scans `ui/packages/*/src`), and the Chat UI could not
  render it.
- **Renders identically.** Everything here was moved, not redesigned: the tokens
  and the button are the Manager's, value for value. A visual change is a change
  of its own, not part of a move.
- **White-label.** `theme.css` is plain `@theme`, never `@theme inline`, so the
  utilities read the variables at runtime and `/branding/brand.css` (BrandDelivery)
  can redefine `--color-primary` and the brand scale after it. `BrandDeliveryTest`
  and `chat-aliases.test.ts` read this file.
- **Strict.** It compiles under the strictest app (the Manager's
  `noUncheckedIndexedAccess` and ES2020 target).
- Checks: `npm run typecheck`, `npm run lint`, `npm test` in this directory. CI runs
  them in the `UI Chat` job.
