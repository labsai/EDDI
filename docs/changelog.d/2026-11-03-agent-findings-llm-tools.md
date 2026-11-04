## 🔒 fix(llm): agent-findings batch llm-tools — tool rate limit, memory ownership and erasure, selector and PDF work bounds, guardrail number parsing (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-llm-tools`)

### What changed and why

The 29 CodeRabbit "agent findings" filed against `modules/llm/tools/**` and `modules/llm/guardrails/**` (scans of 2026-10-26 … 11-02), each re-traced through the current code. Many were already closed by #1160, #1163, #1192 and #1194; the ones still live after those are fixed here:

- **`enableRateLimiting: false` no longer skips the operator's deployment-wide limit.** The flag turned off the whole admission call, so an agent designer's task setting opted out of `eddi.tools.ratelimit.global.*`. It now skips only the task's own per-conversation limit (`ToolRateLimiter.refusedByGlobalLimit`, same bucket, cluster-aware). Documented in [`langchain.md`](../langchain.md) and [`configuration-reference.md`](../configuration-reference.md).
- **`forgetFact` no longer deletes another agent's `global` memory.** Read visibility was treated as permission to delete, so an agent could delete a foreign global key and then write it again with its own value — the replacement `rememberFact` refuses without `allowGlobalKeyOverwrite`. The same rule now applies to deletion ([`user-memory.md`](../user-memory.md)).
- **An update in `rememberFact` cannot recreate a memory a GDPR erasure just deleted.** Updates go through the update-only compare-and-set `replaceIfUnchanged` first; when that no longer applies, the inserting write runs and counts as an insert, so the existing post-write cancellation check takes it back.
- **`extractWithSelector` refuses positional pseudos on a page too wide for them.** jsoup's `:eq`/`:lt`/`:gt`/`:nth-*`/`:*-of-type`/`:only-child` walk every sibling of every candidate — quadratic in one parent's width, CPU the parse timeout cannot interrupt. With such a pseudo in the selector, a page whose sum of squared child counts exceeds 50 million is refused with a message; ordinary tables are unaffected.
- **PDF tool extraction has the ingestion extractor's two missing bounds**: unfiltered page-content references are charged to the decode budget before PDFBox parses them (`PdfTextExtractor.chargePageContents`, now public), and glyph layout stops at the character cap plus slack instead of holding millions of glyphs before the cap is applied — the text laid out so far is kept.
- **The expression guardrail compares against the real downstream number reading.** Both of its readers parsed numbers exactly, so a value only exact parsing refuses (`1e2147483648`) made both skip the same earlier object and agree on a later one while the task's double-based conversion took the earlier. The tolerant reader is now the default mapper, compared number-aware.

### Verdicts

FIXED 9 · ALREADY_FIXED 14 · ACCEPTED_RISK 1 · NEEDS_DECISION 5 · FALSE_POSITIVE 0.

Open decisions (not implemented): process-local tool budget lost on cost-tracker eviction (two findings); quota refunds read failure from result text; `converse_with_agent`'s per-task count resets on a HITL resume; dynamic-agent USE checks treat a `/v1` shared-key conversation's self-asserted user id as a verified principal. The `OutboundHttp` DNS-rebinding residual (a blind request before the post-connect re-check) stays the documented accepted risk in [`security.md`](../security.md#dns-rebinding).
