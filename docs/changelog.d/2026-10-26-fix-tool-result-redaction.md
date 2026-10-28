## 🔒 fix(llm): tool results redacted before the model, bounded selector extraction (2026-10-26)

**Repo:** EDDI (`fix/tool-result-redaction`)

Two findings from a repository security scan of `daecb3513`.

### What changed and why

- **Tool results are redacted before they reach the model.** Only the
  display trace was filtered: an administrator's config-read tool (the Platform
  Operator) showed `<REDACTED>` while the hosted model provider received the
  plaintext key. `ToolLoopRunner.executeToolCall` now runs
  `SecretRedactionFilter.redact` on the result before truncation (the
  `summarize` strategy sends it to a model too) and before the guardrail
  envelope; the raw result stays internal (LAZY activation, repeat guard).
  Trade-off, documented: a key-named value such as `nextPageToken` reaches the
  model as `<REDACTED>`.
- **Selector extraction no longer multiplies nested text.**
  `WebScraperTool.extractWithSelector` copied each match's whole descendant
  text, so nested matching ancestors repeated the same text up to 20 times. An
  element inside an already listed one is skipped, and text is gathered by a
  bounded walk (`boundedText`) against one 20,000-character output budget (5,000
  per element), so nothing past the budget is materialized.

### Design decisions

- **The webhook replay findings of the same scan are not in this change.** Open
  PR #1126 already moves the webhook dedup cache to a cluster-shared bucket and
  refuses instead of evicting; the longer retention for body-signed schemes
  (`github`, `generic-hmac`) follows once it has merged, so the two do not
  conflict in `WebhookEventHandler` and `CacheFactory`.

**Files:** [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`WebScraperTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperTool.java),
[`security.md`](../security.md)
