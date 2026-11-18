## ✨ feat(attachments): one share rule for every agent-to-agent file path — `AttachmentShareService` (2026-11-18)

**Repo:** EDDI (`feat/doc-share-service`) — work package WP-1.1 of the PDF documents plan. No user-visible change: nothing calls `share` yet; the nested-group pass-through now uses the shared read check.

### What changed and why

Agents are about to hand files to each other (delegation, group tasks, handoff, A2A, MCP). Each of those paths would otherwise grow its own copy of "may this conversation pass this blob on?", and the existing one lived privately in `GroupAttachmentBinder.mayPassThrough`. This lifts it into one service in `engine/attachments` that every later path calls.

- **`ConversationListing`** — *the* set of files a conversation's model may name: `listAccessible(conversationId)` (owned or granted) minus the roots the conversation withholds (the caller passes the withheld set, which is memory state). Entries are shown by file name or `(unnamed)`, never by `storageRef`, and are numbered from 1. A store failure gives an empty listing, never an exception. Recorded derived selectors are a parameter but are not consulted yet.
- **`AttachmentSelector`** — `{storageRef, pages, unit, split}`, `pages` authoritative, carried in `Attachment.metadata` under `doc.*` keys; all null = the whole file.
- **`SelectorText`** — the model-facing grammar: an exact known name wins (so `a#u3.pdf` and even `report#u3` are names), else the text splits at the last `#` and the suffix must be `u<n>(.<n>)*` or `p<from>-<to>`, else it is all name.
- **`AttachmentShareService`** (`@ApplicationScoped`): `resolveNames` (exact name, then 1-based number; ambiguous → refused with the numbers; anything else → `not found`, the same text for "elsewhere" and "nowhere"; a part suffix → *parts of a file cannot be shared yet*), `share` and `mayRead`. `share` accepts a selector only when the listing was built for the source conversation, the selector is one of its entries, the root is not withheld (checked again in `share`, so a hand-built listing cannot carry a withheld root) **and** the source can still read the root, then checks MIME type, hop depth, files per share and shares per turn (in that order), grants the target on the root, counts `eddi.attachment.shared{path}` and writes one audit entry. A refusal uses no slot of either cap and counts `eddi.attachment.share.refused{reason}`. Without a store everything refuses; nothing throws.
- **Audit is written by the service**, not returned to the caller: `AuditLedgerService` is an application-scoped bean whose `submit` takes the conversation id on the entry, so a singleton can write to the source conversation's ledger. Entry: `taskId` `attachment.share`, `taskType` `attachments`; output `reason`, `targetConversationId`, `targetAgentId`, `mimeType`, `sizeBytes` — no bytes, no file name and no `storageRef` (a capability; audit entries are returned to their owner).
- **`MemoryKeys.ATTACHMENTS_SHARED`** (`attachments:shared`, rows of file name, target agent, reason — no references). The service holds no memory; `ShareResult.toStepRecords` builds the rows for the calling tool to store.
- **`GroupAttachmentBinder.mayPassThrough`** now calls `AttachmentShareService.mayRead` and then its existing `grantAccess`; it holds no check of its own. The two-argument constructor is kept (it builds a service over the same store, without metrics or audit); `GroupConversationService.attachmentBinder()` passes the injected service. `GroupAttachmentBinderTest` is unchanged and green.
- **Docs:** [attachments-guide.md → Sharing between agents — the rule](../attachments-guide.md#sharing-between-agents--the-rule), [security.md → Files between conversations](../security.md#files-between-conversations), [metrics.md](../metrics.md#attachment-metrics) and the Attachments panel of the Full Metrics dashboard.

### Design decisions

- **Deviation from the plan's signature:** `share` takes a seventh argument, a `ShareParties` record (user id, source agent id and version, target agent id, environment), for the audit entry. There is deliberately no six-argument overload: the user id is what lets the ledger pseudonymise the entry when the user is erased, so every caller must supply it or pass `ShareParties.UNKNOWN` on purpose. The service never loads a conversation to find them — the caller holds the memory. The entry carries no file name.
- **The read check runs before the policy checks**, right after the listing check, so a hand-built or stale listing is refused as `not found` with the requested name withheld (`(unknown file)`), and depth or caps never answer for a file the source cannot read. A call refused as a whole (no store, bad target) reads no store and names no file.
- A selector repeated in one call is shared once (one grant, one slot, one audit entry). Display names are stripped of surrounding whitespace, as is what the model writes, so `notes.txt ` is nameable. A `null` entry in `SharePolicy.allowedMimeTypes` allows nothing rather than emptying the set into "any".
- `SharePolicy.sharesThisTurn == null` is replaced by a counter local to the policy instance. The per-turn cap is checked before the grant and counted after it; that is safe because the tool calls of a turn run one at a time.
- **The listing records its conversation and withheld set** and is sorted by display name (case-insensitive), then reference, so the same files number the same way on every turn; a number is only meaningful against the listing shown in the same turn. A refusal echoes at most 200 characters of what the model wrote.
- `AttachmentShareService.whyNotReadable` gives the reason behind a `false` from `mayRead` (a fixed phrase or the store exception's class name, never its message); the group binder's refusal WARN names it again.
- `GroupAttachmentBinder`'s three-argument constructor rejects a null service, and the binder refuses a pass-through when it has no store to grant through, whatever the service's store says.
- Repeated `grantAccess` is idempotent on both stores by construction (MongoDB `$addToSet`; PostgreSQL appends only when absent). Verified by reading the code; there is no attachment-store IT yet — WP-2a.1 adds the contract ITs.

### Tests

`AttachmentShareServiceTest`, `AttachmentShareAuthorizationMatrixTest` (owner / grantee / stranger × listed / withheld / another conversation's listing × depth below / at the limit, plus a depth chain), `SelectorTextTest`, `ConversationListingTest` and `GroupConversationServiceAttachmentShareTest` (the injected service decides the binder's pass-through), all with 24-hex ids. Mutations run: dropping the listing check, `>=` → `>` in the depth check, and removing the per-turn cap each fail a test.

### Next

WP-1.2: `converseWithAgent(…, attachments)` builds a listing, resolves names, and calls `share` with `ShareReason.DELEGATION`.
