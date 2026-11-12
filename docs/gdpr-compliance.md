# GDPR Compliance Guide

This guide helps EDDI operators handle data subject requests and meet
GDPR/CCPA requirements. What EDDI stores, what leaves the deployment and what
an erasure does to each store is inventoried once, in
[compliance-data-flow.md](compliance-data-flow.md); for an overview of data
processing, see [PRIVACY.md](../PRIVACY.md).

## Handling Data Subject Requests

### 1. Right to Erasure (GDPR Art. 17 / CCPA §1798.105)

When you receive an erasure request:

```bash
# Via REST API
curl -X DELETE https://your-eddi-instance/admin/gdpr/{userId} \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

The response carries a count for every store the cascade touches, plus whether
the cascade actually finished:
```json
{
  "userId": "user-123",
  "memoriesDeleted": 15,
  "conversationsDeleted": 8,
  "conversationMappingsDeleted": 3,
  "logsPseudonymized": 42,
  "auditEntriesPseudonymized": 156,
  "auditEntriesRedacted": 156,
  "attachmentsDeleted": 4,
  "journalEntriesDeleted": 2,
  "checkpointsDeleted": 9,
  "groupConversationsDeleted": 1,
  "sharedArtifactsDeleted": 0,
  "schedulesDeleted": 0,
  "connectionGrantsDeleted": 1,
  "autoVaultedSecretsDeleted": 0,
  "additionalDeleted": {},
  "failedSteps": [],
  "notices": [],
  "complete": true,
  "completedAt": "2026-04-02T15:30:00Z"
}
```

> **Check `complete` before filing the request as fulfilled.** The cascade
> deliberately continues past a failing store, so the categories after it are
> still erased — which is exactly why `conversationsDeleted: 0` on its own cannot
> be read as "this user had no conversations". When any step throws, the endpoint
> answers **207 Multi-Status**, `complete` is `false`, and `failedSteps` names the
> steps that did not run. Some of the user's data is still there: re-run the
> erasure and do not report it to the data subject as done. A cascade in which
> every step succeeded answers **200** with an empty `failedSteps`.
> Besides the step names above, `failedSteps` can also name `clusterStop`, `autoVaultedSecrets`, `deadLetters`, `logBuffer` and a participant by its name (`alerts`, `personalSpaceResources`, `conversationRatings`, `toolQuotas`, ...). The dead-letter purge runs in single-node mode too, not only in a cluster.
>
> The step names are `auditRewrite`, `inFlightConversations`, `runningGroupDiscussions`,
> `userMemories`, `restrictionCache`, `conversationIdLookup`,
> `attachments`, `hitlToolJournal`, `conversationDescriptors`,
> `conversationCheckpoints`, `conversations`, `conversationMappingIntents`,
> `conversationMappings`, `conversationMappingCache`, `groupConversations`,
> `sharedArtifacts`, `schedules`, `oauthStates`, `connectionGrants`, `userMemoriesResweep`,
> `databaseLogs`, `auditRedaction` and `auditLedger`, and they name the stores in the list below
> plus the two cache evictions, the two lookups the cascade needs to reach them,
> and the two signals that stop in-flight work first. `conversationIdLookup` is the worst one to
> see: the id resolution the per-conversation sweeps depend on failed, so those
> sweeps ran over nothing and their zero counts mean "not attempted", not
> "nothing to do".

> **Read `notices` too.** A notice is data a step deliberately did not reach,
> with the remedy; it is not a failure, so it changes neither `complete` nor the
> status code. Each starts with the participant's name and a stable code. The one
> EDDI emits today is `emailLedger: unverifiedDirectoryEmail: …` — the user
> directory records an e-mail address for the user that the identity provider has
> not marked verified, so the email ledger and suppression rows under it were not
> erased (see step 15 for why). Many identity providers never set
> `email_verified`, so for a genuine user this is common. The notice deliberately
> does not repeat the address. The procedure:
>
> 1. Look the address up in the user directory, or ask the data subject.
> 2. Confirm out of band that the address is theirs — for example a reply from that
>    mailbox to a message you sent it.
> 3. Check that **no user account uses the address itself as its user id** (search
>    the user directory for that principal). There is no address-scoped erasure of
>    the email ledger: a request under the address is the whole cascade for whatever
>    principal that string names, so if an account is signed in under that address,
>    `DELETE /admin/gdpr/{address}` would erase that account's data too.
> 4. If no account uses it, run the erasure again with the **address** as the user
>    id: `DELETE /admin/gdpr/{address}`. That reaches the email ledger rows and
>    suppression entries of the address and what the email channel stored under it
>    (step 15, email identities). If an account does use it, do not re-run: clear the
>    suppression entries per email integration with
>    `POST /channelstore/channels/{id}/email-suppressions/clear` (body
>    `{"address": "…"}`), and let the ledger rows expire with
>    `eddi.email.ledger-retention`, or remove them at the database layer.
>
> Exports carry the same field. The same check comes first there: an export under the
> address (`GET /admin/gdpr/{address}/export`) returns everything keyed by that user
> id, so if an account uses the address as its id it would hand that account's data
> to the requester.

**Via MCP:** Use the `delete_user_data` tool with `confirmation="CONFIRM"`. It
reports the same outcome: `status` is `"completed"` only when every step
succeeded and `"partially_completed"` otherwise, alongside the same `complete`
and `failedSteps`.

`__service__` (the owner of service-bound connection grants) is a reserved system
principal, not a user: erasing or exporting it is refused with 400.

**What happens:**
0. The user's in-flight work on the node serving the request is **stopped first**:
   running conversation turns are cancelled (they then skip their write-back to
   user memory and their snapshot is discarded) and running group discussions are
   cancelled immediately. Without this, a turn or discussion still running wrote
   the data back seconds after the erasure reported success. In cluster mode
   (`eddi.messaging.type=nats`, see [Clustering](clustering.md)) the stop is also
   sent to every other replica — keyed by a hash of the user id, never the id
   itself, and that hash sealed (AES-GCM) with `eddi.cluster.signing.key` when one is set, since
   a plain SHA-256 of an e-mail address can be reversed from a list of addresses — and the
   user's dead letters are removed. The erasure waits up to `eddi.cluster.gdpr-stop-timeout`
   (10 s) for every replica of a member count read at that moment; a replica that does not
   confirm, or cannot open the sealed hash (it has a different key), marks the step
   incomplete. Each conversation is first
   marked as erased in a cluster-wide marker (`ERASED` bucket, expiring after
   `eddi.cluster.erasure-marker-ttl`, default `P7D`), then its dead letters are
   purged. A node that was cut off from NATS and kept a dead letter of that
   conversation locally checks the marker before it forwards the entry, and again
   right after publishing it, and discards it (or purges the published copy) when
   the marker is there. Without the marker the entry would be published again in
   the window between the purge and the deletion of the conversation's snapshot.
   A node that stays cut off for longer than the marker's lifetime falls back to
   the check that the conversation still exists, which by then has long failed. Work a replica cannot be
   reached for (NATS down) is stopped by the stores refusing to recreate a
   deleted conversation or group discussion, and step 12 removes any memory it
   managed to write in the meantime. From this point the
   node's audit ledger also writes the user's pseudonym instead of their id, and
   the redaction marker instead of their content, for an hour, so audit entries
   that cancelled work still flushes while it unwinds — or that were already
   queued — do not land raw after step 14. In cluster mode every replica that
   answers the stop does the same before it replies, so its late entries are
   pseudonymised and redacted too; see [Clustering](clustering.md#residual-limitations).
   The in-memory log store is treated the same way (step `logBuffer`): the user id
   is replaced in the ring buffer the log endpoints serve and in the entries still
   waiting for the database writer, and for an hour in new log entries of that
   user, so a queued entry cannot reach the database behind step 13 with the raw
   id. On the erasing node the replacement is the same keyed pseudonym step 13
   writes; a replica that hears of the erasure by hash writes `gdpr-erased`.
1. User memories — **permanently deleted**
2. Binary attachments of the user's conversations — **permanently deleted**. This
   includes the files shared in the user's group discussions (stored under the group
   conversation's id) and the blobs of every member conversation those discussions
   created; the discussions are looked up before step 8 deletes them. A failed lookup
   is reported as `groupConversationAttachments` — and then, as when a blob delete
   under one of those ids fails, step 8 **keeps** the discussion documents (reported
   as `groupConversations`, "kept until their attachments are erased"): they are the
   only record of the ids the blobs are stored under, so deleting them would leave a
   re-run unable to reach the blobs. Re-run the erasure once the store is back
3. HITL tool execution journal entries — **permanently deleted**
4. Conversation descriptors — **permanently deleted**
5. Conversation memory checkpoints — **permanently deleted**
6. Conversation snapshots — **permanently deleted**, unless an earlier step that finds
   its records by conversation id (attachments, dead letters, journal entries,
   descriptors, checkpoints, auto-vaulted secrets) failed: the snapshots are then
   **kept** (reported as `conversations`, "kept until … erased"), because they are what
   lists the conversations under the user, and a re-run needs them to reach what is left
7. Managed conversation mappings — **permanently deleted** (and evicted from their cache)
8. Group conversation transcripts — **permanently deleted** (kept for a re-run when
   step 2 could not finish with their blobs, see there)
9. Shared artifacts owned by the user — **permanently deleted**
10. Schedules owned by the user — **permanently deleted**
11. Pending OAuth authorization flows the user started are invalidated first (a
    callback completing afterwards would otherwise mint a new grant), then the OAuth connection grants (linked accounts) of the user, in every tenant — **permanently deleted**. Each holds a live refresh token for the user's account at the provider; the provider-side consent is not revoked by EDDI and may be revoked by the user there
12. User memories — **re-swept**, for writes that landed while the cascade ran
13. Database logs — userId **pseudonymized** with a *keyed* pseudonym: the audit
    ledger's `gdpr-erased:k1:…` when the ledger signs, otherwise
    `gdpr-erased:s1:…` under a random per-deployment salt kept in the vault's
    metadata store (`gdpr-pseudonym-salt`, created on first use, shared by every
    replica). Neither can be recomputed from a list of candidate ids. Rows erased
    by an earlier release keep their unsalted `gdpr-erased:<sha256>` value — there
    is no migration, as the raw id is gone from them; only when the metadata store
    cannot be read does an erasure fall back to that form (logged at WARN). The log
    message text is not rewritten; bound its lifetime with `eddi.logs.db-retention-days`
14. Audit ledger — rows **kept, content redacted, userId pseudonymized**. The write
    queue is flushed first, so entries still queued are caught. Each row's prompt,
    response, LLM detail and tool calls are replaced by a redaction marker and its
    userId by a keyed pseudonym; a row that verified is re-signed and still
    verifies, a row that did not keeps failing; no row is deleted, so the
    conversation's chain stays intact. `auditEntriesRedacted` counts the rows;
    a row that could not be redacted fails the step (`auditRedaction`) and the
    erasure is not `complete`. Details: [audit-ledger.md](audit-ledger.md#gdpr-erasure-redaction-not-deletion).
    Under `eddi.audit.erasure-mode=pseudonymize` only the userId is replaced and
    the content is **kept** — choose that only when a legal hold requires it
15. Stores that registered as GDPR participants — conversation ratings, tool quotas, alerts, personal-space secrets and variables (`personalSpaceResources`), the user directory, workspace
    notifications and the **A2A task store** (a peer's tasks and context bindings,
    each holding the message it sent and the reply it got, keyed by the peer's
    principal), the **email ledger** (HITL notification rows and conversation-mode
    reply rows addressed to the person, and inbound mail rows sent by them — matched
    through the user id when it is an address or an `email:<integrationId>:<address>`
    identity, and the email the user directory recorded when the identity provider
    marked it verified (`email_verified`); it runs before the
    directory is erased), the **email suppression list** (the addresses a reply
    bounced from permanently, stored as hashes, matched the same way — erasure
    deletes the entry rather than keeping a hash to honour "never mail me again":
    conversation mode only answers mail the person sends, so EDDI writes to them
    again only if they write first),
    and **email identities**: an erasure for an *address* runs this whole cascade
    again for every `email:<integrationId>:<address>` identity of that address —
    the conversations, memories and properties an [email integration](email-integration.md#identity)
    stored under it — for every current email integration and every integration
    the address's inbound ledger rows name. Export collects the same
    identities. A nested erasure that is incomplete fails the step. The one-off
    `email-anon:` identities of unauthenticated senders hold no memory and are not
    linked to an address. All of it is
    **permanently deleted**. Each reports under its own step name
    (`userDirectory`, `workspaceNotifications`, `a2aTasks`, `emailLedger`, `emailSuppressions`, `emailIdentities`).
    **An unverified directory email is not used.** Its address is whatever the account
    holder typed into their identity-provider profile, so matching on it let one
    user's erasure delete — and their export disclose — the ledger and suppression
    rows of somebody else's address. Such an address is skipped; the response says so
    in `notices` (see the procedure above) and the compliance entry records it (below). The `email:<integrationId>:<address>`
    identities need no such check: an email integration mints one only for a sender
    whose mail passed DKIM-aligned authentication, and the address expansion runs
    only for an id the operator named.
    The `GDPR_ERASURE` and `GDPR_EXPORT` entries in the audit ledger record the basis
    on which the email participants matched, as `emailLedgerBasis`: how many
    addresses (`emailAddresses`), where each came from (`emailAddressSources`:
    `userId`, `emailIdentity`, `directoryVerified`) and whether an unverified
    directory email was skipped (`unverifiedDirectoryEmailSkipped`) — never the
    addresses themselves. The field is absent when nothing was matched by address,
    and entries written by earlier releases do not have it. Both entries also carry
    `notices` when there were any, and the `GDPR_EXPORT` entry an `outcome`:
    `completed` when the bundle was handed out, `aborted` when assembling it failed
    after it had started reading — the entry is written either way, with what was
    gathered up to that point
16. **Conversation ratings** the user gave (score, tags, comment) — **permanently
    deleted** by the `conversationRatings` participant, which also forgets the user
    as the *reviewer* of ratings they triaged as a maintainer (those ratings are
    other people's data and stay). Ratings deliberately outlive retention purges
    of transcripts, so no conversation sweep reaches them. See
    [Conversation Ratings](conversation-ratings.md). The `hitlOversight` participant deletes the audit samples of the user's
    conversations and keeps their approval-decision facts without them ([HITL Oversight](hitl-oversight.md#gdpr))
17. **Tool quota counters** (`toolQuotas`) — **permanently deleted** by the `toolQuotas`
    participant: every counter whose subject equals the erased user id. A counter holds a subject
    identifier and a count, no content, and expires on its own an hour after its window ends. When an
    agent's `toolQuotas.subject` template derives an identifier other than the conversation's user id,
    the counters sit under that identifier and are deleted when *that* identifier is erased. The export
    carries the user's counters (tool or pool, window start, count) under `counters` in the
    `toolQuotas` section of `additionalData`, at most 1,000; past that the section says
    `"truncated": true` and the bundle reports `complete: false`

### 2. Right of Access (GDPR Art. 15) / Data Portability (Art. 20) / Right to Know (CCPA §1798.100)

```bash
curl https://your-eddi-instance/admin/gdpr/{userId}/export \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -o user-data.json
```

The export is a structured, machine-readable JSON bundle of these categories — not
yet every category EDDI erases; what it leaves out is named in `omittedCategories`
(see the note below):
- All persistent user memories
- Conversation outputs (the chat history as recorded in the conversation outputs). Conversation properties, step data and checkpoints are **not** exported
- All managed conversation mappings (intent→conversation bindings)
- Audit processing records: conversation id, agent id, task type, duration, LLM detail and timestamp (capped at 10,000 entries; the prompt input, output and tool calls of the entry are not included)
- Attachment metadata (conversation, storage reference, file name, MIME type, size) —
  the binaries themselves are not inlined and must be fetched via the attachment
  download API
- Linked OAuth accounts (`connectionGrants`: tenant, connection, status, scopes and
  dates) — never token material
- Conversation ratings the user gave (`conversationRatings`: conversation and step,
  agent and version, score, tags, comment, triage status and note — without the
  reviewing maintainer's identity; capped at 10,000). Processing restriction (Art. 18)
  blocks new ratings
- HITL oversight (`hitlOversight`): audit samples of the user's conversations (redacted tool arguments) and the approval-decision
  facts of their conversations and decisions (capped at 10,000 each)
- Alerting (`alerts`): for a user who runs agents, the alert rules they created or last
  edited — id, name, times and which role — and the alert events they acknowledged —
  id, rule name, source, agent, fired and acknowledged times; for a user who chatted,
  the `OUTPUT_MATCH` values alerts stored from their conversations (`outputMatchValues`:
  value, conversation, time). No other person's identity; capped at 10,000 each.
  Erasure blanks those values and forgets the user as acknowledger and rule author
- A2A tasks the user, as a peer, created (`a2aTasks`: task and context ids, the
  message sent, the reply, state and timestamps)
- Group discussions the user started (`groupConversations`: question, transcript,
  state and dates; capped at 1,000, `groupConversationsTruncated` when the cap bit),
  and the shared artifacts of those discussions (`sharedArtifacts`: content and
  revision history; capped at 1,000, `sharedArtifactsTruncated`). The attachment
  metadata above includes the discussions' files and those of their member
  conversations. A store that fails is named in `failedCategories`

**Other people's identifiers are masked** (Art. 15(4)). A record reached by the
subject's id can name someone else, and the bundle keeps the fact that another
person was involved but not who: their id becomes `[other person]`. That covers the
approver (`decidedBy`) of a decision in the subject's conversation, the owner
(`userId`) of a conversation the subject decided in, the reviewer (`reviewedBy`) of an
audit sample, and the other human members of a group discussion the subject started
(speaker and target id and display name in `transcript`). The subject's own id and
`system:*` principals are kept. In a transcript the rule fails closed: an id stays
only if the stored discussion itself recorded it as an agent at the time — it started
a member conversation for it, recruited or created it, or added it as a dynamic
member of a non-human type — and every other id is masked. The group's *current*
configuration can only add masks (its `HUMAN` members are always masked); it is never
taken as proof that an id is an agent, because an id that was a human member during
the discussion may be listed as an agent today. Human evidence wins over agent
evidence in every entry: an id is masked throughout if the configuration lists it as
human, the discussion recorded a human turn for it (paused for one or skipped one),
or one of its entries is a human contribution or a timed-out human turn — even if a
later continuation started a member conversation for the same id. So an agent that
never got a member conversation (one that was only ever skipped, say) reads
`[other person]` too. **Exports of discussions created before this version show their
agents as `[other person]` as well:** those discussions carry no record of their human
members, and a human turn written as an opinion or a vote left no other trace, so agent
evidence is not trusted for them and every id except the subject's own (and
`system:*`) is masked. What another person *wrote* in a discussion
the subject started stays in the transcript; review it before handing the bundle over.
(Earlier releases exported those identifiers as stored.)

> **Check `complete` before handing the bundle to the data subject.** The endpoint
> answers **200** only when the bundle covers everything EDDI holds on the user,
> and **207 Multi-Status** otherwise. Several things can make it incomplete, and each
> is named in the payload:
>
> - `omittedCategories` — personal-data categories this exporter does not reach.
>   **Today this list is never empty**: schedules and HITL journal entries are
>   erased by the Art. 17 cascade as this user's personal data but are not yet
>   exportable (group transcripts and shared artifacts are exported since the
>   2026-10-17 review). So the export endpoint currently answers **207 on every
>   request** and `complete` is always `false`. It becomes 200 when those two
>   exporters land.
> - `conversationsTruncated` — the per-request conversation cap (1,000) bit,
>   because each snapshot is a full document load assembled in memory on the
>   request thread. `totalConversations` says how many the user actually has, and
>   the omitted ones remain retrievable individually through the conversation API.
> - `failedConversationIds` — conversations the exporter could not load at all,
>   listed by id, and therefore absent from `conversations`. Retry the export or
>   fetch those ids individually.
> - `groupConversationsTruncated`, `sharedArtifactsTruncated` and
>   `failedCategories` — the same two signals for the group sections.
>
> A bundle with `complete: false` is **not** a complete Art. 15 / Art. 20
> response — do not file the request as fulfilled on it. (The audit-record
> cap of 10,000 is silent: the bundle does not flag it and `complete` ignores it, so
> a user with more audit rows than that needs a supplementary extract. A failure
> while reading the memories, mappings, audit, attachment or grant blocks is likewise
> only logged and does not appear in `failedCategories`.)
>
> 207 rather than 206 Partial Content, which earlier releases sent: 206 is a range
> status and RFC 9110 requires a `Content-Range` alongside it, which this endpoint
> neither reads nor produces. Any client still branching on 206 needs updating.

**Via MCP:** Use the `export_user_data` tool. It reports the same fields in the
payload — `complete`, `omittedCategories`, `conversationsTruncated`,
`totalConversations` and `failedConversationIds` — and an agent acting on its
answer must not report an incomplete bundle as fulfilled.

### 3. Right to Restriction of Processing (GDPR Art. 18 / LGPD Art. 18)

When a user disputes data accuracy or objects to processing, you can
freeze their processing without deleting data:

```bash
# Restrict processing
curl -X POST https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"

# Check restriction status
curl https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
# Returns: true/false

# Remove restriction
curl -X DELETE https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

**What happens when restricted:**
- New conversation creation is blocked → returns **403 Forbidden**
- Message processing (`say`) is blocked → returns **403 Forbidden**
- Scheduled [Dream](user-memory.md#dream-consolidation) cycles for the user are
  skipped: no prune, no consolidation, no LLM call over their memories. The fire
  completes, so the schedule resumes once the restriction is lifted. A restriction
  status that cannot be read skips the cycle too (nothing is processed); it is not
  a failed fire, so a store outage cannot dead-letter the schedule
- Existing data is preserved (not deleted)
- Restriction status is stored as a user memory entry under the reserved key
  `_gdpr_processing_restricted` (category `gdpr`). Keys starting with `_gdpr_` are
  reserved for this bookkeeping: agents (`rememberFact`/`forgetFact`), property
  setters, the user-memory and properties REST endpoints and the MCP memory tools
  all refuse to write or delete them, the "delete all memories" surfaces keep them,
  and retention and Dream maintenance never touch them. Only this admin endpoint
  sets or lifts a restriction, and lifting removes every row under the key
- All restriction/unrestriction events are logged in the audit ledger

**Caching the restriction flag — `eddi.gdpr.restriction-cache-ttl-seconds` (default `0`, i.e. no caching)**

The flag is read at conversation start and again on every `say`/`sayStreaming`, so it sits on
the hottest path in the system. **By default it is nonetheless read from the store every
time**: no verdict is cached, a restriction applied on any replica takes effect on every
replica at once, and a store outage always answers **503**
(`ProcessingRestrictionUnavailableException`) rather than being absorbed by a cached verdict.
The cost of that default is one indexed lookup per turn.

Setting the property above `0` switches on a **node-local** cache with that TTL.
`restrict`/`unrestrict` publish through it, so the node serving the admin call applies the
change on the very next turn. In cluster mode the eviction is also sent to every other node, but
an event can be lost while NATS is unreachable, and then **another node keeps answering "not
restricted" from its own cache until the TTL expires** — and every node keeps answering from
cache during a store outage instead of failing closed. A cached negative verdict is a suspended
Art. 18 legal control, which is why it is not the default.

- **Several replicas** — keep the default (`0`), cluster mode included: cross-node invalidation
  is best-effort while NATS is unreachable.
- **Single node, or a cluster with conversation affinity** — every turn of a conversation and
  every admin call reach the same node, which is what makes the explicit invalidation
  sufficient. Setting e.g. `eddi.gdpr.restriction-cache-ttl-seconds=30` there buys back the
  per-turn lookup. Treat it as an explicit topology assertion, not a tuning knob: it is wrong
  the moment a second replica is added.

**Use cases:**
- User disputes accuracy of stored data (Art. 18(1)(a))
- Processing is unlawful but user requests restriction instead of erasure (Art. 18(1)(b))
- User objects to processing pending verification (Art. 21)

### 4. Response Timeline

| Regulation | Initial Deadline | Extension |
|---|---|---|
| **GDPR** | 30 days | Up to 90 days for complex requests |
| **CCPA** | 45 days | Up to 90 days with notification |

EDDI's erasure and export are normally quick for a typical user, though a user with
very many conversations, or a cluster that has to stop work on several nodes, takes
longer. The timeline constraint is usually your internal DSAR process, not the
technical execution.

## Retention Configuration

```properties
# Auto-delete ended conversations after N days (default: 365, -1 to disable).
# A database migrated from EDDI 5 holds this sweep until
# eddi.migration.v6-rename.retention-confirmed=true; the dev profile overrides these defaults.
eddi.conversations.deleteEndedConversationsOnceOlderThanDays=365

# End idle conversations after N days (default: 90; -1 disables)
eddi.conversations.maximumLifeTimeOfIdleConversationsInDays=90

# User memories — delete entries older than N days (default: -1, disabled)
eddi.usermemories.deleteOlderThanDays=-1

# Persisted database logs — delete entries older than N days (default: -1, kept)
eddi.logs.db-retention-days=-1
```

**Deleted conversations follow the same clock.** Deleting a conversation without
`deletePermanently=true` (the Manager's default) ends it, hides it from every
listing and denies access to anyone but its owner and admins. Its memory stays
until `deleteEndedConversationsOnceOlderThanDays` has passed since its **last
interaction**, and is then removed with the other ended conversations. With the
default of 365 days, a conversation deleted this way can stay stored for up to a
year. Use `deletePermanently=true` or a GDPR erasure when it has to go at once.
(Earlier releases purged such a conversation at the next daily sweep if it had
already ended, and never if it was still open.)

**A deletion is all or nothing.** The retention sweep and `deletePermanently=true`
remove the same things: the conversation's auto-vaulted secrets, attachments,
memory checkpoints (copies of its properties) and HITL tool-journal entries, then
its memory and descriptors. If any of the dependent deletes fails — or the secrets
vault is unavailable while the conversation references auto-vaulted secrets — the
memory and descriptors are kept: the permanent delete answers with an error and can
simply be retried, and the sweep tries again on its next run. (The sweep used to
skip checkpoints and tool-journal entries entirely, and both paths used to delete
the memory after a failed attachment or checkpoint delete, which left those copies
with nothing pointing at them.)

**Ending idle conversations does not restart the retention clock.** Both the
daily idle sweep and the admin bulk end
(`POST /conversationstore/conversations/end-inactive`, see
[Upgrading from EDDI 5](upgrading-from-5x.md)) set the conversation to `ENDED`
without touching its descriptor, so `deleteEndedConversationsOnceOlderThanDays`
keeps counting from the **last interaction**.

**Per-category retention** allows different retention periods for:
- **Conversations** — 365 days (default)
- **User memories** — disabled by default (configure per-deployment)
- **Database logs** — disabled by default; a daily sweep deletes persisted log
  entries older than `eddi.logs.db-retention-days`. Earlier releases documented
  this retention as configurable without any code that applied it

**The audit ledger has no retention property — by design.** `IAuditStore` is an
append-only contract that deliberately exposes no update or delete operation, so
EDDI never time-expires audit entries. Erasure requests are satisfied by
**redacting** the erased user's rows — their content replaced by a marker, their
`userId` by a pseudonym — not by deleting them. See
[Audit Ledger Legal Basis](#audit-ledger-legal-basis) below for the legal basis.
If your jurisdiction requires time-limited audit retention, implement it at the
operational or database layer (archival job, partition drop, storage-level TTL);
EDDI provides no application-level audit purge.

> **Operator note:** earlier releases shipped an `eddi.audit.retentionDays`
> property in `application.properties`. No code ever read it, so setting it was a
> silent no-op — no audit entry was ever deleted by it. The property has been
> removed; if your deployment sets `eddi.audit.retentionDays` (or
> `EDDI_AUDIT_RETENTIONDAYS`), drop it and use database-level archival instead.

### The audit dead-letter sink holds personal data, and erasure does not reach it

When the ledger cannot persist an entry it writes that entry to the dead-letter
sink — in cluster mode the `eddi.<prefix>.dlq.audit` subject of the JetStream
dead-letter stream while NATS takes it, otherwise the JSONL file at
`eddi.audit.dead-letter-path` (default
`/opt/eddi/data/eddi-audit-deadletter.jsonl`). **The record is the whole audit
entry**: the `userId`, the verbatim prompt and response, the LLM detail and the
tool calls, plus the HMAC and agent signature. It has to be, or a dropped entry
is neither replayable nor usable as evidence of what the ledger itself lost.

The consequence for Art. 17 is that the sink is a **second location holding
personal data that the erasure cascade does not touch**. `DELETE
/admin/gdpr/{userId}` redacts the ledger and pseudonymizes the database logs;
nothing rewrites the JSONL file or the JetStream subject. Secret *redaction* has already
been applied to the entry (see
[Secret Redaction](audit-ledger.md#secret-redaction)), but user content has not,
because preserving the entry is the whole point of the sink.

As the controller you must therefore:

- [ ] Treat `eddi.audit.dead-letter-path` (and, in cluster mode, the
      `eddi.<prefix>.dlq.audit` JetStream subject) as an audit-data location in your record of processing
      activities, with the same access controls and encryption at rest as the
      ledger itself.
- [ ] Include it in the erasure procedure: either replay and truncate it once
      the store is healthy again, or pseudonymize the affected records by hand.
      `eddi_audit_entries_dropped_total` counts per process and starts at zero
      on every restart, so a zero value does not prove the sink is empty. Check
      the JSONL file on every node and, in cluster mode, the
      `eddi.<prefix>.dlq.audit` subject of the dead-letter stream
      (`nats stream subjects <prefix>_DEAD_LETTERS`) before concluding there is
      nothing to do.
- [ ] Give the JSONL file a retention period: as with the ledger, EDDI never
      expires it. The JetStream stream is different: it drops its entries after
      `eddi.coordinator.dead-letter.max-age` (default `7d`), whether or not they
      were replayed, and it is their only copy — replay within that window.

A non-empty dead-letter sink is an incident, not a steady state — see
[Incident Response](incident-response.md).

**Data minimization (Art. 5(1)(e)):** Review the default retention periods
and reduce them to the minimum necessary for your use case.

## What erasure and export do not reach

Erasure covers every store EDDI keys by a user — the steps and the
participants listed above. Export is narrower: it is the bundle described under
[Right of Access](#2-right-of-access-gdpr-art-15--data-portability-art-20--right-to-know-ccpa-1798100),
and what it leaves out — schedules and HITL journal entries among them — is named in its
`omittedCategories`, so an export must not be handed over as complete without
reading that field. Beyond both, several classes of data sit outside EDDI's reach
altogether, each for a different reason; a controller's record of processing
should name them and the measure that bounds each one.

| Data | Why it is out of scope | What bounds it |
| --- | --- | --- |
| **RAG knowledge bases** (`ragstore`, pgvector, external vector stores) | Agent-level reference material an editor ingests for the agent, not records about a user. Nothing in a knowledge base is keyed by a user id, so no per-user sweep is possible — and personal data ingested as a source is a document the controller chose to publish to the agent, removed through the knowledge base's own source management | Review sources before ingestion, and delete a source that holds a data subject's record through the RAG store's source endpoints (see [rag.md](rag.md#ingestion-sources)) |
| **Application log files** (console, files a log shipper collects) | Written as text to destinations EDDI does not control and cannot rewrite afterwards. The persisted database logs *are* pseudonymised (step 13); the console copy is not. Credential material is redacted before any record is written, and user ids appear only where the logging context carries them | Keep file and shipper retention short, and treat the collected logs as a processor's data set in your record of processing |
| **Telemetry exports** (OpenTelemetry spans, Micrometer metrics) | Spans carry conversation and agent ids and timings, never message content or user ids; metrics are aggregates. Once exported they are the collector's data, outside EDDI's reach | Retention is set on the collector or metrics backend |
| **Approver identity in other users' HITL tool journals** | The journal is deleted by conversation id, so an approver's `decidedBy` entry inside another user's journal survives the approver's own erasure | Treat approver ids as pseudonymous staff data and bound them with the journal's retention |
| **Group discussions the user took part in but did not start** | The group store matches the discussion's owner, so only discussions the user started are erased or exported | Handle shared transcripts in your DSAR process |
| **LLM-provider retention** | Every LLM call sends the message and its context to the configured provider, a processor under its own DPA; EDDI cannot recall what the provider has logged | Choose providers with zero-retention terms, or self-host (Ollama, jlama); see [LLM Provider Data Flow](#llm-provider-data-flow) |

The dead-letter sink of the audit ledger is a further case, treated above under
[its own heading](#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it).

## Audit Ledger Legal Basis

The EDDI audit ledger is retained indefinitely — check the legal basis for
your deployment; the usual candidates are:

1. **GDPR Art. 17(3)(b)/(e)** — compliance with a legal obligation, or the
   establishment, exercise or defence of legal claims
2. **EU AI Act Arts. 12/19 (and 26(6) for deployers)** — record-keeping and
   automatically generated logs of high-risk AI systems

What those bases cover is the record that processing happened — which task ran,
when, for which agent version, at what cost, emitting which actions — not the
erased person's words. An erasure therefore keeps every row and its chain
position but removes the person from it: the `userId` is pseudonymized and the
recorded content (prompt, response, LLM detail, tool calls) is replaced by a
redaction marker. If your legal hold requires the content itself, set
`eddi.audit.erasure-mode=pseudonymize` and document why.

## Controller Checklist

As the data controller, you must:

- [ ] **Privacy Policy**: Document your use of EDDI and AI processing
- [ ] **Legal Basis**: Determine and document the legal basis for each
      processing activity (see PRIVACY.md for suggestions)
- [ ] **Consent**: Obtain consent before enabling conversational AI
      (if consent is your legal basis)
- [ ] **DPAs**: Establish Data Processing Agreements with:
  - Your EDDI hosting provider (if not self-hosted)
  - Each cloud LLM provider configured in your agents
- [ ] **User Notice**: Inform users that conversations are processed by AI
- [ ] **Retention**: Review default 365-day retention — adjust if needed, and
      set `eddi.logs.db-retention-days` (database logs are kept until deleted
      by default)
- [ ] **Memory Disclosure**: If using `enableMemoryTools`, inform users their
      interactions are remembered across sessions
- [ ] **Provider Selection**: Choose LLM providers that meet your data
      residency requirements (see provider table in PRIVACY.md)
- [ ] **Article 30 Register**: Document EDDI as a processing system in your
      records of processing activities
- [ ] **Erasure Process**: Document your internal process for handling DSARs
      using the GDPR API
- [ ] **Breach Response**: Prepare a breach notification plan (see
      [incident-response.md](incident-response.md))
- [ ] **CCPA Disclosure**: If serving California consumers, document that
      EDDI does not sell personal information

## LLM Provider Data Flow

**Conversation content leaves your deployment** on every LLM call, to the
provider configured on the agent — a third party and a processor unless you
self-host the model (Ollama, jlama). What is sent is inventoried in
[What reaches the LLM provider](compliance-data-flow.md#what-reaches-the-llm-provider):
besides the user message, the history window and the templated system prompt,
it can include the user's persistent memories — among them `global` memories
**other agents** wrote about the same user — retrieved documents and tool
results. Provider data locations and DPAs are listed in
[PRIVACY.md](../PRIVACY.md#supported-llm-providers-and-data-locations).

**For maximum data sovereignty**, use Ollama or jlama with self-hosted models.

## CCPA-Specific Requirements

The CCPA requests use the same two endpoints as GDPR, with the same results and
the same limits — read the sections above, not a shorter version of them.

### Do Not Sell (§1798.120)

EDDI does **not sell personal information** and has no mechanism to do so.
Document this in your CCPA privacy notice.

### Right to Know (§1798.100)

Use the export endpoint (`GET /admin/gdpr/{userId}/export`). It does **not**
yet cover every category: schedules and HITL journal entries are listed in
`omittedCategories`, so today the response is
always `207` with `complete: false` — see
[Right of Access](#2-right-of-access-gdpr-art-15--data-portability-art-20--right-to-know-ccpa-1798100).
Supplement those categories by hand before answering the consumer.

### Right to Delete (§1798.105)

Use the erasure endpoint (`DELETE /admin/gdpr/{userId}`) and check `complete`.
It deletes every store except two, which it keeps under §1798.105(d)
(legal obligation): the audit ledger (content redacted, identifier
pseudonymized) and the database logs (identifier pseudonymized). The audit
dead-letter sink and backups are not reached — see
[Right to Erasure](#1-right-to-erasure-gdpr-art-17--ccpa-1798105).

## International Privacy Regulations

EDDI's GDPR/CCPA infrastructure covers the technical requirements of
all major international privacy frameworks. Each regulation has a detailed
feature mapping and deployer checklist in PRIVACY.md:

- **[PIPEDA](../PRIVACY.md#pipeda--canada)** — Canada (10 Fair Information Principles)
- **[LGPD](../PRIVACY.md#lgpd--brazil)** — Brazil (Art. 18 data subject rights)
- **[APPI](../PRIVACY.md#appi--japan)** — Japan (2022 amendments, EU adequacy)
- **[POPIA](../PRIVACY.md#popia--south-africa)** — South Africa (8 processing conditions)
- **[PDPA](../PRIVACY.md#pdpa--southeast-asia)** — Singapore, Thailand & Malaysia
- **[PIPL](../PRIVACY.md#pipl--china)** — China (data localization, cross-border transfer)

The same erasure and export endpoints (`DELETE /admin/gdpr/{userId}`,
`GET /admin/gdpr/{userId}/export`) work for all jurisdictions. EDDI
provides the technical layer; deployer checklists cover organizational
measures (consent, officer appointments, breach notification).

## See Also

- [PRIVACY.md](../PRIVACY.md) — Data processing overview and international regulations
- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA deployment guide
- [eu-ai-act-compliance.md](eu-ai-act-compliance.md) — EU AI Act compliance
- [compliance-data-flow.md](compliance-data-flow.md) — Data flow diagram for auditors
- [incident-response.md](incident-response.md) — Breach response runbook
- [templates/baa-template.md](templates/baa-template.md) — Business Associate Agreement template
