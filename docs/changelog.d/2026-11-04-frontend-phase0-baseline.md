## ⚡ perf(ui): frontend unification phase 0 — bundle budgets, per-locale fonts, a lighter widget (2026-11-04)

**Repo:** EDDI (`perf/frontend-phase0-baseline`)

Phase 0 of the frontend unification plan (revision 3, branch `claude/amazing-thompson-jzrvz7`, `planning/frontend-unification-plan.md` §7): measure first, take the two quick wins, write down where Open WebUI is the better answer, and record what each conversation surface can do today.

### First load, gzip -9, before any lazy chunk

| Page | Before (main, 2026-11-04) | After | Budget |
|---|---|---|---|
| `/manage`, `/workforce`, `/welcome` | 743.8 KB | 616.4 KB | 630 KB |
| `/chat` | 224.8 KB | 160.6 KB | 170 KB |

KB are 1000 bytes. The plan's §1.2 measured 714 KB and 217 KB on 2026-10-29; main grew in between, so the "before" column is re-measured on today's `origin/main`. `/manage`, `/workforce` and `/welcome` still load the identical file set — separating them is phase 5.

### What changed and why

- **Budget check in CI.** [`ui/scripts/check-bundle-budget.mjs`](../../ui/scripts/check-bundle-budget.mjs) reads each built HTML shell, sums the gzipped scripts, module preloads and stylesheets it references, and compares them with [`ui/bundle-budget.json`](../../ui/bundle-budget.json). Server-rendered files (`/__*__.js`, `/branding/*`) are skipped by name; any other reference missing from `dist/` fails the check rather than shrinking the number. `UI Manager Checks` and `UI Chat` now run `vite build` and `npm run budget` after their tests. Raising a budget is a decision (plan §8), not a fix.
- **Manager: script fonts per locale.** [`main.tsx`](../../ui/manager/src/main.tsx) imported all eight Noto families; their `@font-face` rules were 128 KB gz of the 158 KB main stylesheet. Only Noto Sans stays eager. [`locale-fonts.ts`](../../ui/manager/src/i18n/locale-fonts.ts) imports a family's CSS when its locale becomes active: `ar` → Arabic, `th` → Thai, `hi` → Devanagari, `ja` → JP, `ko` → KR, `zh`/`zh-CN`/`zh-Hans` → SC, `zh-TW`/`zh-HK`/`zh-MO`/`zh-Hant` → TC (from the detected tag; the Manager ships one `zh` locale). The first paint waits up to 3 s for the active locale's font CSS, so a Japanese user does not see a font swap. **Text in another script inside a Latin UI** (a Japanese agent reply in an English Manager) still renders: `--font-sans` names every family, and where one is not loaded the browser falls through to `system-ui`, which has these scripts on every desktop and mobile OS. It is drawn in the system's face rather than in Noto.
- **Widget: no react-router.** [`location.ts`](../../ui/chat/src/location.ts) parses the three shapes — `/chat/managed/:intent/:userId`, `/chat/:environment/:agentId/:userId`, `/chat/:environment/:agentId` — with react-router's matching rules (case-insensitive literals, `managed` outranks `:environment`, trailing slashes ignored, params percent-decoded). Unknown paths still go to `/chat/production/default`, and `?token=` is still removed with `history.replaceState`. [`ChatLocationProvider`](../../ui/chat/src/components/ChatLocationProvider.tsx) replaces `BrowserRouter`, and its `initialPath` mode replaces `MemoryRouter` in the tests. `location.test.tsx` pins every URL shape, because embedders link to them.
- **Widget: raw-HTML parser on demand.** `rehype-raw` (and parse5 with it) is now a lazy chunk like KaTeX and highlight.js, loaded when a reply contains `<` followed by a letter, `/`, `!` or `?`. Per D9 the widget **still renders sanitised HTML for every agent**; only the loading changed. Once the chunk is in, the output is the same: [`raw-html-lazy.test.tsx`](../../ui/chat/src/components/raw-html-lazy.test.tsx) compares the two pipelines on a corpus. The two byte-level differences of parse5's round trip (whitespace text hoisted before a table, `data-footnote-ref=""` rather than `"true"`) render the same and are normalised in the comparison. While the chunk loads, a reply's HTML is briefly missing and its text shows.
- **Docs (D10).** [`open-webui-integration.md`](../open-webui-integration.md) and the README now say that a deployment wanting only generic chat can use Open WebUI over `/v1`, and list what it goes without: approvals in the chat, quick replies and `inputField`, secret input, ratings, review notices, group discussions, memory transparency, branding.
- **Feature matrix.** [`ui/manager/e2e/feature-matrix.spec.ts`](../../ui/manager/e2e/feature-matrix.spec.ts) has one cell per capability (KaTeX, highlight.js, output images, quick replies, `inputField`, secret input, history, inline HITL decision, i18n) and surface (`ChatPanel`, Operator, Workforce member thread), tagged `@surface:` and `@capability:`. `yes` cells assert against MSW. `no` cells are `test.fixme` with the reason. `unit` cells are supported but unreachable in MSW (no activated operator, no agent asking for a secret) and are skipped with the unit tests that cover them. The widget's column is [`ui/chat/src/feature-matrix.test.tsx`](../../ui/chat/src/feature-matrix.test.tsx), a Vitest spec, because `ui/chat` has no Playwright tier.

### Not done, or not verified

- The Manager feature-matrix spec was **not run locally**: the installed Playwright browser build does not match `@playwright/test` 1.64.1, and downloading the browser was out of scope for this session. CI's `UI Manager E2E (MSW)` runs it.
- The widget's matrix is unit-level, not Playwright. Adding Playwright to `ui/chat` is a lockfile change better done with phase 1's single root lockfile.

### Next

Phase 1 (workspaces, one lockfile, `@eddi/core`) and phase 2 (catalog, Workforce for `eddi-user`) can start. The remaining 616 KB of the Manager's first load is mostly its own code, which phase 5 splits.

```decision-log
| 2026-11-04 | Budgets are the phase-0 result plus ~2–6 % headroom (630 KB Manager, 170 KB widget), in KB of 1000 bytes of gzip -9 | Plan §2.6/§7 phase 0 asks for budgets from measured numbers | Budgets at the plan's §1.2 numbers (too loose to catch regressions after the wins); KiB (the plan's table mixes units) |
| 2026-11-04 | The widget's feature-matrix column is a Vitest spec, not Playwright | `ui/chat` has no Playwright tier; adding one is a lockfile change on Windows | Driving the widget from the Manager's Playwright config (couples the two apps' installs) |
```
