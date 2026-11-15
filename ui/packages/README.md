# Shared UI packages

The npm workspace (`ui/package.json`) includes every directory under `ui/packages/`.
It is empty for now. The frontend unification plan puts three packages here, each
consumed as source through a workspace link, with no build or publish step:

| Package | Contents |
| --- | --- |
| `@eddi/core` | Framework-free TypeScript: HTTP, the SSE frame parser, the conversation and group APIs, contract types |
| `@eddi/ui` | Design tokens, primitives, theme, i18n setup |
| `@eddi/conversation` | Controllers and components for every kind of conversation |

Apps may import packages; packages never import apps.

A new package needs `ui/package-lock.json` regenerated (`npm install` at `ui/`) and,
because CI runs `npm run lockfile:check`, the Windows caveat in
[`ui/manager/AGENTS.md`](../manager/AGENTS.md) applies.
