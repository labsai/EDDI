## 🐛 fix: conversation listings leave out descriptors whose conversation is gone (2026-10-01)

**Repo:** EDDI (`fix/6.5-release-readiness`)

A rehearsal on real 5.5.1 data found that 1,106 of 1,301 conversation descriptors had no
conversation memory left. They were already orphans on 5.x, never marked deleted. Once the v6
rename gave them an `agentResource`, `GET /conversationstore/conversations?agentId=` returned them
as conversations that cannot be opened: 852 entries for an agent with 189 conversations. It also
logged one WARN per orphan per page, about 51,000 lines for seven listings.

`RestConversationStore`:
- `populateDataToDescriptor` now reports a missing snapshot, and the listing skips that
  descriptor instead of returning it.
- The per-orphan message is DEBUG. A listing that skipped any counts them once in the new metric
  `eddi.conversations.listing.orphaned_descriptors`.
- A snapshot that exists but names an agent whose descriptor is gone is still listed, as before.
- The counter is in [`metrics.md`](../metrics.md) and charted as a second series on the Full Metrics
  Reference panel for listings, beside `owner_scan_exhausted`.

The test that pinned the old behaviour ("descriptor with null snapshot should still be added") is
replaced by a by-agent case: an orphan and a live conversation for the same agent, where only the
live one is listed. Mutation-checked: listing the orphan anyway, or not counting it, fails it.

[`upgrading-from-5x.md`](../upgrading-from-5x.md) §6 says to compare the by-agent listing with
`conversationmemories`, and names the metric.

The idle sweep's message now reads "has been idle for N days, longer than the maximum idle time of
M days". Before, it said the conversation was "N days older than" the limit, which gave the idle
time as if it were the excess over the limit.
