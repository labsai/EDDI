## 🐛 fix(manager): the NEGOTIATION preset drift test failed on every Windows checkout (2026-09-29)

**Repo:** EDDI (`fix/negotiation-test-crlf`)

### What changed and why

`hitl-config-negotiation.test.ts` checks that the Manager's arbitration prompt
matches `DiscussionStylePresets.TEMPLATE_ARBITRATION` verbatim, by reading the
`.java` file and parsing the text block per JLS 3.10.6. The parser split on `\n`
only. A Windows checkout (`core.autocrlf`) has CRLF line terminators, so every
line kept a trailing carriage return, a line-end `\` continuation was no longer
at the end of its line, and the lines were never joined — the test failed on
Windows while passing on Linux CI.

The parser now normalises CR and CRLF to LF before anything else, which is what
javac itself does (JLS 3.10.6 step 1). A new case feeds it a CRLF source
directly, so the behaviour is pinned on every platform, not only on Windows;
removing the normalisation fails it and the real drift check.

**Files:** [`hitl-config-negotiation.test.ts`](../../ui/manager/src/lib/__tests__/hitl-config-negotiation.test.ts)
