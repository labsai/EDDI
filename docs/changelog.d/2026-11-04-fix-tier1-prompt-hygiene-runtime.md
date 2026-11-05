## 🛡️ fix(groups, llm): fenced peer text in group prompts, head-and-tail tool truncation, identifiers kept in summaries, retry jitter (2026-11-04)

**Repo:** EDDI (`fix/tier1-prompt-hygiene-runtime`)

Four small changes that came out of comparing EDDI with OpenClaw's group-chat and agent runtime.
Each one is local to one or two files.

### 1. Peer text in group prompts is fenced, cleaned and bounded

The preset discussion templates quoted other participants as `— {entry.speaker}: "{entry.content}"`.
A peer is not necessarily trustworthy (a dynamic or recruited agent, a human member, or a model
talked into something), and its text could close the quotation and carry on as if it were the
template giving instructions. The text could also be any size.

- **New `PeerContentGuard`** ([`PeerContentGuard.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PeerContentGuard.java)),
  applied once in `GroupContextBuilder.buildPhaseInput` to the template data of every phase type,
  custom `inputTemplate`s included. It breaks any ` ``` ` inside quoted text with a zero-width space,
  reduces a speaker name to one line of at most 120 characters, cuts one entry at 12,000 characters
  with a note, and drops the oldest entries of a list once it passes 96,000 characters, leaving one
  `System` note in their place. The newest entry is always kept. The stored transcript is unchanged.
- **The 14 presets that quote peers** ([`DiscussionStylePresets.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java))
  now put that text between ` ``` ` fences, including `targetResponse` and `originalResponse`, and
  open with `QUOTED_CONTENT_NOTICE`, which tells the member that fenced text is quoted material to
  weigh and not instructions to follow.
- The participant names spliced into the instructions themselves (`targetName` in "You are reviewing
  {targetName}'s…", the speaker's own `displayName`) get the same one-line treatment, and the list
  budget counts every rendered string of an entry (names and phase name too), not only its content.
- The Manager's verbatim copy of the arbitration prompt (`NEGOTIATION_ARBITRATION_TEMPLATE` in
  [`hitl-config.ts`](../../ui/packages/app-kit/src/lib/hitl-config.ts)) follows the new preset; a Vitest
  compares the two.
- The user's own `question` is not treated as peer text.
- **Not covered yet:** the task-force templates (`TEMPLATE_EXECUTE`, `TEMPLATE_VERIFY`), which
  `TaskForceEngine` renders from its own data. They quote task results the same unfenced way.

### 2. Tool results over the limit keep their start and their end

`ToolResponseTruncator` cut an over-limit result after its first `maxChars` characters, which drops
exactly what a model most needs from a failed command or API call: the exit code, the error and the
totals at the end.

- **New strategy `headTail`**, the default when `toolResponseLimits.truncationStrategy` is unset. It
  keeps 70% of the limit from the start and 30% from the end, with a `[TRUNCATED: …]` note in place
  of the middle. The kept content is still exactly the limit, and neither cut splits a surrogate pair.
- `truncate` (start only) is unchanged when set explicitly, and is still what an unknown strategy
  name and the `paginate`/`summarize` fallbacks use.
- `toolResponseLimits` had no documentation, so it now has its own section in
  [`langchain.md`](../langchain.md#tool-response-limits).

### 3. Summaries keep identifiers exactly as written

The rolling conversation summary's default prompt ([`ConversationSummarizer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java))
gained a seventh rule: copy every opaque identifier (IDs, UUIDs, hashes, order and ticket numbers,
URLs, hostnames, IPs, ports, file names and paths) exactly as written. The group context-window
summary (`GroupContextBuilder.WINDOW_SUMMARY_INSTRUCTIONS`) got the same line. A custom
`summarizationPrompt` replaces the whole default, so it gets none of this; the docs now say so.

### 4. Retry backoff has jitter

`RetryConfiguration` slept exactly `backoffDelayMs × multiplier^n`, so every conversation that hit
the same provider outage retried in lockstep and arrived in one burst again.

- **New field `jitterRatio`**, default `0.2`: each configured backoff is shortened by a random share
  of up to that fraction (a 1000 ms backoff sleeps 800–1000 ms). Applied in both `executeWithRetry`
  and the public `backoff(...)` used by the streaming path.
- **Downwards only**, so a jittered sleep never exceeds `maxBackoffDelayMs`, the 60-second total
  backoff budget or the turn deadline. A provider-requested wait (`honorRetryAfter`) is never
  shortened. `0` turns it off; values outside `[0, 1]` are clamped and `NaN` counts as `0`.
- The Manager's `retry` type gained the optional field ([`types.ts`](../../ui/packages/app-kit/src/components/editors/llm/types.ts)).

### Behaviour changes operators may notice

- A tool result over its limit now shows its last 30% instead of more of its start. Set
  `"truncationStrategy": "truncate"` to keep the old output.
- Retry sleeps are up to 20% shorter and no longer identical across conversations.
- Group member prompts show peer text in fences, with a one-line notice at the top.

### Tests

New `PeerContentGuardTest`, including a real Qute render in which a peer's attempt to close the
fence stays inside it, and a check that every peer-quoting preset carries the notice. Also new
head-and-tail cases in `ToolResponseTruncatorExtendedTest`, jitter cases in
`RetryConfigurationTest`, and an identifier-rule case in `ConversationSummarizerTest`. Existing
retry tests that assert exact sleep values or exact budget arithmetic
(`RetryConfigurationTest`, `RetryConfigurationDeadlineTest`, `StreamingLegacyChatExecutorRetryTest`,
`ToolExchangeIdsAndProvenanceTest`) now set `jitterRatio` to `0`: jitter only shortens a sleep, so
with it on, one more retry fits inside the same budget.

```decision-log
| 2026-11-04 | Retry jitter only shortens the backoff (`[base × (1 − r), base]`), never lengthens it | Adding ±20% jitter as OpenClaw does | Symmetric jitter, which could push a sleep past `maxBackoffDelayMs`, the total backoff budget or the turn deadline |
| 2026-11-04 | Peer-content caps (12,000 per entry, 96,000 per list) are engine constants, not group config | Bounding peer text in group prompts | A per-group setting: it would have to be threaded through every `buildPhaseInput` caller for a safety bound nobody has needed to tune; the I9 context window is the tuning knob |
| 2026-11-04 | `headTail` became the default only for an *unset* strategy; explicit `truncate` and the fallbacks keep start-only | Making head-and-tail truncation the default | Changing `truncate` itself, which would have silently changed configs that asked for it by name |
```
