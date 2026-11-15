## Review fixes: percent-encoded widget routes and CJK glyph precedence (2026-11-15)

Branch `chore/ui-npm-workspaces` (PR #1274), UI only.

- `ui/chat/src/location.ts`: the literal `chat` and `managed` segments are now percent-decoded before they are compared, as the router this parser replaced did, so `/%63hat/production/agent-1` and `/chat/%6danaged/support/user-7` resolve to their intended routes. Regression cases added to `location.test.tsx`.
- `ui/manager/src/index.css`: `:root:lang(ja|ko|zh|zh-Hant|...)` rules put the active locale's CJK family first in `--font-sans`, so a Han glyph both families cover no longer keeps the glyph form of an earlier-loaded locale after a language change. `locale-fonts.test.ts` pins the ordering.
