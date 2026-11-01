## Manager: adapt Translate type to i18next 26.5 (2026-11-01)

- Repo/branch: EDDI, `dependabot/npm_and_yarn/ui/manager/minor-and-patch-c71a93672b` (PR #1155).
- i18next 26.5 made `TFunction`'s overloads stricter, so it no longer matched the Manager's own `Translate` helper type (optional third argument). `Translate` in `ui/manager/src/lib/operator/blocked-calls.ts` is now an exported two-overload interface; `top-discussions.tsx` reuses it, and `cascade-issues.tsx` passes `issue.params ?? {}`. No runtime change.
