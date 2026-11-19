## Email channel: addressing, @mentions, routing order and per-target senders (2026-11-19)

**Repo:** EDDI · **Branch:** `feat/email-addressing` (stacked on `fix/inbound-mail-slack-hardening`, #1394) · **Plan:** `planning/email-channel-v2-plan.md` §7 (Phase 3, on `docs/email-channel-v3-control-plan`)

**What changed:**

- **`actWhen`** (common key, `always` | `addressed`, default `always`). With `addressed` a mail runs a turn only when it addresses the integration: its `In-Reply-To` is a reply this integration sent, the sender's own text mentions a target, or the mailbox (a plus-address, an alias, a target address) is newly in `To`. Anything else is refused `not_addressed` (ledger reason, metric outcome, audit entry), after the loop guard, sender lists, authentication and replay detection, and before the budgets. New pure class `integrations/email/EmailAddressing`. The Manager preselects `addressed` for new integrations only.
- **`aliases`** (common key, up to 20 addresses): count as the integration's own — mail from them is refused like mail from `address` (loop guard and blocklist), they are other integrations' "own" addresses too, and they must be unique across integrations.
- **Targets**: `ChannelTarget` gains email-only `mentions`, `addresses` and `allowedSenders`, validated and normalised in `RestChannelIntegrationStore` and refused (non-empty) on every other channel type.
- **Strict mention extractor** `engine/mail/MentionExtractor`, separate from `QuoteStripper` (which now only exposes its history markers): reads the head region — down to the first quoted line, attribution, header block (any language: a label followed by an address or a time), separator or signature delimiter, at most 20 non-blank lines and 2,000 characters — and nothing when text and HTML diverge.
- **Routing order** (`EmailTargetRouting.route` now returns a `RouteDecision`): target address in `To`, then `Cc`; plus-tag; mention; subject word; default. `mail.addressing` and `mail.route` (`target`, `source`, `alsoAddressed`, and `redirectRefused`) reach the agent's context and the input template.
- **Continuations (F3)**: a mail continuing an existing conversation keeps its owner agent, even when its route is a GROUP target; when it explicitly named another target the context carries `mail.route.redirectRefused` and an INFO line is logged.
- **Per-target `allowedSenders` (F4)**: checked for the routed target and for a continued conversation's own target; refused `blocked:target_not_allowed`, never rerouted to the default.
- **Thread reply cap (F2)**: `LedgerEntry.threadKey` (`sha256(integrationId + "\n" + threadRoot)`, via the new shared `EmailThreads`) on reply rows, in MongoDB (field + index) and PostgreSQL (`ADD COLUMN IF NOT EXISTS thread_key`, index `(integration_id, thread_key, kind, created_at)`); `maxRepliesPerThreadPerHour` counts the thread across its conversations, and the conversation's own count still applies. Upgrade note in [`upgrading-from-6.5.md`](../upgrading-from-6.5.md#email-maxrepliesperthreadperhour-counts-the-mail-thread).
- Every addressing, routing and `mail` context read uses #1394's signed view (`InboundMail.signedView()`); tests replay a signed mail with an unsigned `To`, `Cc` or `Subject` added.
- Docs: [`email-integration.md`](../email-integration.md) (Addressing EDDI, Mentions, Routing order, Per-target senders, keys and context tables), [`metrics.md`](../metrics.md). MCP `create_channel_integration` description, Manager (Addressing section, target rules editor, 23 new strings in all 11 locales), Platform Operator prompt (revision 63).

**Design decisions:**

- `LedgerEntry` keeps an 18-argument constructor that delegates with `threadKey = null`; only the stores, the in-memory fake, the contract and the reply sender use the 19-argument one.
- The thread cap refuses when **either** the thread count or the conversation count reaches it: the thread root comes from `References`, which a sender controls, so the thread key may only ever make the cap stricter.
- A redirect is recorded only for explicit addressing (target address, plus-tag, mention); a subject word or the default never counts as asking. In trigger mode with `correlateThreads` the correlation key still includes the routed agent, so a mail routed elsewhere starts that agent's conversation rather than being a redirect.
- A continued conversation whose memory cannot be read (other than "not found") defers the mail rather than skipping the owner's `allowedSenders`.
- An unknown stored `actWhen` (only possible around the save-time validation) reads as `addressed`, the quieter mode.
- Target `addresses` must be unique across targets, and an alias may not be another integration's address or alias.
- Phase 2 (the mail log) is not on this base: "the mail log shows it" is the ledger row's state and `lastError`.

```decision-log
| 2026-11-19 | Email addressing (`actWhen: addressed`) is a noise filter, not a security boundary; who may make an agent act stays with authentication, the sender lists and per-target `allowedSenders` | Email v2 plan §7: `In-Reply-To` is unsigned at Gmail | A default-on "must be addressed" check (aliases, lists and BCC deliver unaddressed mail) |
| 2026-11-19 | `maxRepliesPerThreadPerHour` counts the mail thread and the conversation, refusing at whichever is reached first | Email v2 plan F2: N senders in one thread got N times the cap | Counting the thread key alone (its root comes from sender-controlled `References`) |
```
