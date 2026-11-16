## 📝 docs(planning): email channel v2 — addressing, @mentions, thread replies, dedicated Gmail mailboxes (2026-11-16)

**Repo:** EDDI (`docs/email-channel-v2-plan`)

### What changed and why

A plan only; no code changes. [`planning/email-channel-v2-plan.md`](../../planning/email-channel-v2-plan.md) is written so a coding agent can implement it without further context. It covers people addressing agents inside ordinary email (an agent's address in To, or `@finance` in their own text, also when EDDI is only in Cc), staying quiet unless addressed while keeping a readable trail, optional replies the internal thread can see, consulting another agent, and dedicated Google Workspace mailboxes over OAuth.

Six phases, each with config keys, code locations, data-model changes for both datastores, REST, Manager, operator, docs, tests and acceptance criteria:

1. Doc corrections and a real-provider verification matrix (Gmail app password, M365 shared mailbox).
2. A mail log, a Message-ID lookup and an owner digest.
3. Addressing (`actWhen`), a strict mention extractor separate from `QuoteStripper`, routing order, per-target `allowedSenders`, aliases, a real per-thread reply cap.
4. A mailbox-scoped OAuth grant (`Binding.MAILBOX`) for Workspace.
5. `replyAudience: thread`, with a memory-free `email-thread:` identity.
6. Agent consultation through `converseWithAgent`.

The review behind it found four defects, which the plan fixes in its phases:

- `docs/email-integration.md` promises domain-wide delegation and an admin-linked SERVICE grant, both refused by the v1 plan or the code.
- `maxRepliesPerThreadPerHour` counts per conversation, not per thread.
- A continuation silently ignores which agent a mail addressed.
- Subject-word routing lets any allowed sender reach any target.

[`planning/email-channel-plan.md`](../../planning/email-channel-plan.md) carries "Amended by" notes at §7.1, §9 and the Phase 6 row.

### What's next

Phase 1 (doc corrections, provider matrix), then Phase 2 (mail log) before Phase 3, which starts refusing unaddressed mail. Phase 4 can run in parallel. Owner question open: GDPR erasure of one participant in a thread-mode conversation (plan §13 Q4).

```decision-log
| 2026-11-16 | Google Workspace mailboxes use a mailbox-scoped grant (`Binding.MAILBOX`, auth-code, keyed `mailbox:<address>`, usable only by the mail transport, account verified against the address on link and refresh) for dedicated mailboxes only; no domain-wide delegation | Product owner: dedicated mailboxes only, enterprise first. Run-as-principal (v1 Phase 6) would key the grant to the linking admin, the wrong owner | Run-as-principal per the RAG plan; domain-wide delegation; using a person's own mailbox |
| 2026-11-16 | Conversation-mode replies may also Cc the answered mail's To/Cc recipients that match an explicit internal allowlist (`replyAudience: thread`, opt-in); such integrations run as a memory-free `email-thread:` identity | Product owner wants answers visible to the thread, made safe. Per-sender long-term memory would otherwise leak to colleagues | Keeping "never Cc" (v1 §9); reply-all to every recipient; per-mail identity choice (a conversation's user id cannot change mid-thread) |
| 2026-11-16 | Approving a consultation of another agent stays in the Manager or Slack via `toolApprovals`, never by replying to a mail | v1 §22 bans reply-to-approve | "Reply yes to allow" in the thread |
```
