## Operator revision bump after #1112 (2026-10-20)

**Repo:** EDDI · **Branch:** `fix/operator-revision-36`

#1112 (classifier guardrail) was squash-merged at a head whose `system-prompt.ts`
edits were in, but whose matching `operator-revision.json` bump was pushed a
moment later and never reached `main`. `operator-revision.test.ts` therefore
fails on `main`. This sets `ui/manager/src/lib/operator/operator-revision.json`
to revision 36 with the fingerprint the test computes for the current prompt,
allow-list and gate (`d9679ddf62f6532a`). No behaviour change.
