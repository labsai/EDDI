# Shared UI packages

The npm workspace (`ui/package.json`) includes every directory under `ui/packages/`.
The frontend unification plan puts three packages here, each consumed as source
through a workspace link, with no build or publish step:

| Package | Contents | Status |
| --- | --- | --- |
| [`@eddi/core`](core/README.md) | Framework-free TypeScript: HTTP, the SSE frame parser, the conversation API, HITL status, attachments, ratings, contract types | in use by both apps |
| [`@eddi/ui`](ui/README.md) | Design tokens, the theme provider, the i18next setup with lazy locales and per-locale fonts, the primitives both apps share | in use by both apps (phase 3a) |
| [`@eddi/conversation`](conversation/README.md) | Controllers and components for every kind of conversation: `AgentConversation`, the one markdown renderer, the shared `conversation.*` strings | phase 3b: the agent kind; 3c: host hooks, the Manager chat on it |

Apps may import packages; packages never import apps; `@eddi/core` imports neither
React nor `@eddi/ui`; `@eddi/ui` makes no API call (no `@eddi/core`, no `fetch`, no
`window.__EDDI_*__`); `@eddi/conversation` imports no router and reads no
`window.__EDDI_*__`, and neither of the other two imports it. `sharedPackage` in `ui/eslint.config.base.js` enforces this at
lint time, and `core/src/boundaries.test.ts` checks it again and fails on an import
cycle across every package here.

A package is imported by its npm name (`import { … } from "@eddi/core"`), and an
app lists it as a dependency (`"@eddi/core": "*"`). Its `exports` point at
`src/index.ts`, so Vite, Vitest and `tsc` read the TypeScript directly. Because each
app compiles the package with its own settings, a package's `tsconfig.json` must be
at least as strict as the strictest app (the Manager's `noUncheckedIndexedAccess`).

A new package needs `ui/package-lock.json` regenerated (`npm install` at `ui/`) and,
because CI runs `npm run lockfile:check`, the Windows caveat in
[`ui/manager/AGENTS.md`](../manager/AGENTS.md) applies: regenerate the lock on Linux
(`docker run --rm -v <ui>:/w -w /w node:26 npm install --package-lock-only`).
