## 🧪 test(manager): the NEGOTIATION preset drift test reads CRLF Java sources (2026-09-29)

**Repo:** EDDI (`test/manager-crlf-java-source-parsing`)

### Why

`ui/manager/src/lib/__tests__/hitl-config-negotiation.test.ts` checks that the Manager's
`NEGOTIATION_ARBITRATION_TEMPLATE` matches the `TEMPLATE_ARBITRATION` text block in
`DiscussionStylePresets.java`, reading the Java file with `readFileSync`. Its little text-block
parser split on `\n` only. On a Windows checkout with `core.autocrlf=true` the Java file has CRLF
endings, so every line kept a trailing `\r`. The trailing-whitespace strip (`[ \t]+$`) left it in
place and the line-end `\` continuation check (`/\\$/`) never matched. The test failed there with
the expected text still full of `\`-newline continuations. CI checks out LF, so it only ever failed
on Windows.

### What changed

- `javaTextBlock` normalises CR/CRLF to LF before it parses anything. JLS 3.10.6 does the same as
  the first step of text-block processing, so the parser now follows the spec more closely and
  checks no less than before.
- New parser test, "reads a CRLF source exactly like its LF twin", builds a CRLF block
  in-memory, so it guards the fix on Linux CI too. Mutation-checked: without the normalisation,
  this test and the real drift test both fail.
- Checked the other Manager tests that read files from disk (`workforce-wave3-parity`,
  `session-log-store-not-at-boot`, `openapi-contract`, `chat-prose-rhythm`,
  `design-sync-tsconfig`, `route-integrity`). They pass on the same CRLF checkout, and no other
  test reads `src/main/java`, so none of them changed.
