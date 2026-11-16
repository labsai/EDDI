## ♻️ refactor(email): opaque mailbox cursor and a processedAction vocabulary for the next mail transports (2026-11-05)

**Repo:** EDDI (`refactor/mail-transport-cursor`)

### What changed and why

`MailTransport.MailboxSession` was IMAP-shaped (`uidValidity()`, `uidNext()`, `messagesAfter(long lastUid, …)`), and
the poller and the state store kept UIDs. The Gmail API transport (cursor = `historyId`) and the Microsoft Graph
transport (cursor = a mail-folder `deltaLink`) that come next must each be one class with no poller change (email plan
§7.2, Phases 4 and 6). No behaviour change for IMAP: every existing email test passes with its assertions unchanged.

- **`MailboxCursor`** (new, `integrations/email/transport`) — opaque, `<transport>:<position>`; the poller never reads
  inside it. **`ImapCursor`** is IMAP's reading (`<UIDVALIDITY>:<last UID>`, exactly the two values stored before).
- **`MailboxSession`** is now `startCursor()` and `messagesAfter(MailboxCursor, int) → Batch`. A `Batch` carries
  entries (each message with the cursor that settles it), a `start` cursor (kept when nothing is settled), an `end`
  cursor (stored when everything is settled — for a history transport it may lie past the last message) and
  `cursorReset`, which replaces the poller's "UIDVALIDITY changed" branch: same WARN, same
  `eddi.email.poll.cursor_resets`, same rescan deduplicated by the ledger.
- **`UidFolderSession`** (new) — the IMAP cursor logic on three UID primitives; `ImapMailTransport.ImapSession`
  extends it. A cursor written by another transport is treated as a reset.
- **`MailboxMessage.uid()`** is a default (`-1`); only UID sessions use it.
- **State store** — `MailboxState` carries a `MailboxCursor` (`uidValidity()`/`lastUid()` remain as derived IMAP
  views); `saveCursor(…, MailboxCursor, …)`, with the old `(uidValidity, lastUid)` overload as a default method.
  MongoDB adds the field `mailboxCursor`, PostgreSQL the column `mailbox_cursor` (`ADD COLUMN IF NOT EXISTS`).
  `StoredCursor` maps both the same way.
- **`processedAction`** is parsed by the new `EmailProcessedAction`: `flag`, `move:<folder>`, `label:<name>`.
  `flag` and `move:` behave as before; `label:` is refused at save time for `imap` ("needs a transport with labels;
  IMAP has none") because IMAP marking only ever sets the fixed keyword `$EddiProcessed`. `EmailChannelSettings`
  carries the parsed action; `moveToFolder()`/`movesProcessed()` are derived from it.
- **Find by Message-ID** was already a transport method (`MailboxSession.findByMessageId`); no shared code searched
  IMAP, so nothing moved.

### Design decisions

- **Persisted state stays compatible in both directions.** An IMAP cursor is written to the new serialised field
  *and* to the legacy `uidValidity`/`lastUid` (`uid_validity`/`last_uid`); for IMAP the legacy fields win on read.
  State written before this change has no serialised cursor and loads as the same IMAP cursor, without migration.
  A node still on the old version (rolling upgrade, rollback) only writes the legacy fields, so letting them win
  means a stale serialised cursor can never override them. A non-IMAP cursor is stored only in the new field, with
  the legacy fields at 0 ("no IMAP cursor").
- `MailboxCursor.toString()` names the transport only — a Graph delta link does not belong in a log line.
- Test edits are mechanical: `EmailMailboxPollerTest`'s fake session is now `new UidFolderSession()` instead of
  `new MailboxSession()`, and the test-only `InMemoryEmailMailboxStateStore` stores a cursor.

Docs: [`email-integration.md`](../email-integration.md) (`processedAction` row, the ledger section on the cursor).

**Files:** [`MailboxCursor.java`](../../src/main/java/ai/labs/eddi/integrations/email/transport/MailboxCursor.java),
[`ImapCursor.java`](../../src/main/java/ai/labs/eddi/integrations/email/transport/ImapCursor.java),
[`UidFolderSession.java`](../../src/main/java/ai/labs/eddi/integrations/email/transport/UidFolderSession.java),
[`StoredCursor.java`](../../src/main/java/ai/labs/eddi/integrations/email/ledger/StoredCursor.java),
[`EmailProcessedAction.java`](../../src/main/java/ai/labs/eddi/configs/channels/model/EmailProcessedAction.java),
`MailTransport.java`, `ImapMailTransport.java`, `EmailMailboxPoller.java`, `IEmailMailboxStateStore.java`,
`MongoEmailMailboxStateStore.java`, `PostgresEmailMailboxStateStore.java`, `EmailChannelSettings.java`.
Tests: `MailboxCursorTest`, `StoredCursorTest`, `EmailProcessedActionTest`, `EmailMailboxPollerCursorTest`.

### Next

The Gmail API transport (Phase 6) and the Graph transport (Phase 4) as one `MailTransport` each, plus a transport
registry so the poller picks the transport by `platformConfig.transport` instead of injecting `ImapMailTransport`.
