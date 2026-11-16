## Shared UI packages move to @types/node 26 with the apps (2026-11-16)

**Repo / branch:** labsai/EDDI, carried by `feat/catalog-workforce-end-users` (#1273) to turn `main` green again.

**What changed:** Dependabot's #1431 bumped `@types/node` to `^26.6.6` in `ui/manager` and `ui/chat` and dropped 24.19.2 from `ui/package-lock.json`, but `ui/packages/core`, `ui/packages/ui` and `ui/packages/conversation` still asked for `^24.19.2`. `npm ci` then refused the lock (`Missing: @types/node@24.19.2 from lock file`), so every UI job on `main` failed at Install. The three shared packages now ask for `^26.6.6` too, in their `package.json` and in their workspace entries in the lock (edited by hand, so the Windows pruning of the wasm32-wasi children described in `ui/manager/AGENTS.md` cannot happen).

**Why it slipped through:** the Dependabot PR was opened before the shared packages existed and was never rebased, so its own CI saw only the two apps.
