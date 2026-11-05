# @eddi/app-kit

What the two signed-in apps — the Manager (`ui/manager`) and Workforce (`ui/workforce`) —
share. Consumed **as source** through the npm workspace link, with no build step, like the
other packages here.

Phase 5 of the frontend unification plan (`planning/frontend-unification-plan.md`, §7)
split Workforce out of the Manager into its own app. Workforce rendered about two hundred
of the Manager's modules — the API client and its hooks, the Keycloak session, the UI
primitives, the group and chat components, the group editors its team settings embed — and
the rule for the split is that the apps share code only through packages. Those modules
moved here, at the paths they had under the Manager's `src/`, with `git mv` so their history
follows (`git log --follow`).

## Where it sits

```
@eddi/app-kit        the signed-in apps' shared layer — router, API calls, window.__EDDI_*__ allowed
  ├── @eddi/conversation   router-free, no window.__EDDI_*__
  ├── @eddi/ui             no API calls
  └── @eddi/core           no React, no DOM beyond fetch
```

It may use everything below it; nothing below it may use it, and it never imports an app.
`sharedPackage` in `ui/eslint.config.base.js` and `core/src/boundaries.test.ts` enforce both.
It is not router-free or API-free on purpose: it is app code, shared by two apps. Code that
the Chat UI could also use belongs in one of the three packages below instead.

## What is in it

| Path | What |
| --- | --- |
| `lib/api-client.ts`, `lib/api/*` | The authenticated REST client and the API modules both apps call |
| `hooks/*` | Their TanStack Query hooks; `use-app-navigate.ts` |
| `components/auth/*` | `AuthProvider`, the Keycloak session, `AuthContext` |
| `components/ui/*`, `components/shared/*` | Primitives (badge, dialog, skeleton, …) and shared pieces; `app-link.tsx` (`AppLink`, `AppNavigate`) |
| `components/chat/*`, `components/groups/*`, `components/editors/*`, … | The components both apps render: `ChatPanel`, the group editors, the discussion panel. The team components (task board, decision card, structured turns, the overview) are `@eddi/conversation/team`'s since phase 6 |
| `hooks/use-group-discussion-stream.ts` | The apps' binding of `@eddi/conversation`'s `GroupDiscussion` controller: a transport over `lib/api/groups`, one controller per group writing its slot of `useGroupStreamStore` |
| `components/layout/app-providers.tsx`, `lib/start-app.tsx` | The provider stack and the boot both `main.tsx` files use |
| `lib/app-paths.ts` | Which app serves a path (`/manage`, `/welcome` → Manager; `/workforce` → Workforce) and whether that is the running one (`import.meta.env.VITE_EDDI_APP`, set by each app's `vite.config.ts`) |
| `i18n/app-i18n.ts`, `i18n/locales/*` | `setupAppI18n`: the strings of this package's components in eleven locales, deep-merged into each app's bundle with `@eddi/conversation`'s (the agent kind's and, through `withTeamLocales`, the team components') |
| `styles/app.css` | The base stylesheet (tokens, markdown, typography, scrollbars, prose rhythm); `@source` makes Tailwind scan this package |
| `test/*` | The shared Vitest setup (`setup-common.ts`), `test-utils.tsx` (`renderPage`, `renderAs`, …), the route-integrity and app-boundary suites, the group rendering matrix fixtures, and the E2E fixtures (`test/e2e/*`) |
| `test/mocks/*` | The MSW mock API both apps run against in Vitest and under `npm run dev` |

## Using it

Import by path, not from an index — there is none, so an app's first load holds only the
modules it reaches:

```ts
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
```

`package.json` `exports` maps each directory to its files (`./hooks/*` → `./src/hooks/*.ts`),
with an explicit entry for a `.tsx` in a `.ts` directory or the reverse.
`core/src/boundaries.test.ts` fails on a module the map does not reach, so a new file in a
mixed directory gets its entry in the same change.

Moving between the apps is a page load: a `<Link>` to the other app's path matches nothing
in this app's router. `AppLink`, `AppNavigate` and `useAppNavigate` use the router within
the app and a full navigation across; each app's `route-integrity.test.ts` fails on a
router link into the other one.

## Checks

`npm run typecheck` and `npm run lint` here (CI: `UI Workforce`). Its tests run in the two
apps' suites, which exercise it: the Manager's Vitest coverage measures this package too.
