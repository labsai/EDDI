## 🔒 feat(email): the per-target email approval gate — approval before acting (2026-11-21)

**Repo:** EDDI (`feat/email-approval-gate`, on top of `fix/email-guard-start-paths`) — phase C4 part 1 (§8.1 and §8.3)
of the email channel v3 control plan. Reply review (§8.2) and everything under `emailControl` come later.

### What changed and why

An approved sender can still ask for something that should not happen on their word alone. Each AGENT target of an
email integration now takes `approvalPolicy` (`none` < `actions` < `firstContact` < `everyTurn`), `readOnlyTools`
(tool-approval patterns) and `rejectedReply`, on `ChannelTarget`. A turn tainted `origin=email` whose target has a
policy carries it in the reserved context key `emailApproval` (`EmailApprovalGate.CONTEXT_KEY`, removed from client
context by `ClientContextGuard`, read exactly from the current step), so a HITL resume next week reads what the mail's
turn carried.

- **Its own gate, never a merge into `toolApprovals`.** `ToolLoopRunner` applies `EmailApprovalGate.overlay` right
  after the agent gate's `classify`: every call the agent's gate let through and `readOnlyTools` does not name moves to
  the gated side (reason `email-gate:not-read-only`), whatever `exempt`, `task-approvals.mode=replace` or the agent's
  timeout say. `readOnlyTools` never un-gates. With no agent config at all (or the tool-HITL kill switch) the loop
  gets an empty config while the gate is armed, so the pause machinery works. Live path and resume both pass through
  this point, so the execution journal and the repeat check apply unchanged: a reissue of an executed approval is
  gated again (by `REPEAT_REASON`, or by the email gate where the agent gate is inert). The built-in loop helpers
  `discover_tools` and `fetch_tool_response_page` are never held; an answer tool's reply is extracted before any gate.
- **Armed by the taint.** The gate needs `origin=email` from the memory, the thread, or the step's own
  `context:turnOrigin` — the last because a new conversation's `CONVERSATION_START` turn runs before
  `ConversationService` records the taint on the memory, and a cascade step may run on a pool thread.
- **Timeout.** `ConversationHitlService.populateHitlTimeoutBookmark` makes a pause the email gate caused (a
  pre-run pause, or a tool batch holding an email-gated call) `AUTO_REJECT` after `eddi.email.approval-gate.expiry`
  (default `PT72H`); any other pause of a mail turn keeps the agent's policy but never auto-approves.
- **Rule-driven calls refused.** `ApiCallsTask`, `McpCallsTask` and `LlmTask`'s `httpCallRag` refuse a tainted turn's
  call unless `readOnlyTools` names it (httpcalls as `http.<method>:<path>`, `http:<name>`, name; mcpcalls as
  `mcp:<tool>`, tool, call name). They have no loop to pause in.
- **Waiting before the agent runs** (`firstContact`, `everyTurn`). `Conversation.executeWorkflows` throws a RULE pause
  on the first workflow at task index `-1` once the turn's data is on the step and before its first task — for a new
  conversation inside `init`, i.e. before `CONVERSATION_START`, so no model is called; `resume` then continues at
  index 0 and runs the whole turn. The step datum `emailGate:preRun` marks it: a rejection ends the turn with
  `rejectedReply` (conversation output `emailGate: rejected`, read by `EmailReplySender` instead of its generic text),
  an edited answer is ignored on approval. A new conversation's mail cannot be submitted into a paused conversation,
  so `EmailInboundProcessor` closes its row `DONE` as `email_gate:awaiting_start` (the start context carries the mail
  so the pause notice and the approver see it); on approval `EmailReplySender.onResumeCompleted` puts the row back to
  `RECEIVED` as `email_gate:start_approved` and the next poll resubmits the mail by Message-ID without a second wait.
  First contact: no earlier answered `DONE` row from that sender on the integration whose conversation belongs to the
  target's agent (`EmailApprovalNotes.NOT_A_CONTACT` excludes rejected, waiting, skipped and failed rows);
  unauthenticated senders are always first contact.
- **Validation.** `RestChannelIntegrationStore.validateEmailApprovalFields`: email integrations only, refused on GROUP
  targets, `readOnlyTools` validated by `ToolApprovalPatterns` (≤ 100), `rejectedReply` ≤ 2000 characters. Save
  warnings `email.approval-policy-none` and `email.rule-calls-refused` (`EmailAgentToolsInspector`); the MCP channel
  tools explain both.
- **Manager.** The email target card has an approval section (policy, read-only tool chips, rejected reply); new email
  targets start on `actions`; switching a target to GROUP drops the fields; both warnings have toasts; 11 locales.
  Operator prompt section added (revision bump).
- **Docs:** "Keeping an email agent in bounds" in `docs/email-integration.md`.

### Design decisions

- The policy travels in a reserved context key rather than a new memory field: it is per turn, survives a resume on
  the step, and needs no store migration in either datastore. `ChannelTarget` is serialised with the integration
  document, so both stores keep the new fields as they keep the rest.
- The pre-run pause is a RULE pause at index `-1`, so approvals page, MCP, notifications and the in-thread notice work
  unchanged.
- The pause reason names target and authentication, never the address or the subject: a pause reason reaches the
  write-once audit ledger.
- `emailControl.review.expiry` is not in scope; the expiry is a deployment property until that object lands.

### What's next

- §8.2 reply review on top of `emailControl`; read the expiry from `emailControl.review.expiry` then.
- Delegated conversations of a mail turn carry the taint but no target policy; the delegating call itself is gated.
  The same holds for a scheduled check-back with `conversationStrategy: new`: booking it is a gated tool call.
- (The items about a lost `start_approved`, the missing reconciliation sweep and the `READY` conversation left by a
  rejected start are fixed by the entry below.)
- `AgentOpsToolsProvider.budgetProposalIsGated` still asks only the agent gate (stricter: the budget tools are then
  not offered).

## 🔒 fix(email): the approval gate holds beyond the mail's own turn (2026-11-22)

**Repo:** EDDI (`feat/email-approval-gate`) — findings of an independent review of the entry above.

### What changed and why

- **Later turns are gated too (HIGH).** The gate reached a turn only in the mail turn's context, so a
  `CONTINUE_CONVERSATION` turn or a scheduled check-back of a mail conversation ran tainted but ungated. The gate a
  mail turn carries is now recorded on the conversation — `IConversationMemory.getEmailApprovalGate`, snapshot field
  `emailApprovalGate` next to `origin`, sticky, written by `Conversation.executeWorkflows` through
  `EmailApprovalGate.record` — without the per-turn `preRun` flag or the held mail. `EmailApprovalGate.of` reads the
  step's gate, else the recorded one; `preRunRequested` lets a new turn without a gate of its own inherit
  `everyTurn`'s wait (not a rerun, not the start). `policy: "none"` is written explicitly now, so "no gate" differs
  from "unknown": a tainted conversation the email channel started (a step carries `context:emailIntegration`) with
  no recorded gate fails closed (`actions`, nothing read-only). Delegations and group members never carry that marker.
- **The approval binds the mail, not its Message-ID.** The ledger row gets two columns, `approvalState` and
  `contentHash` (SHA-256 of the raw mail), in both stores (`ADD COLUMN IF NOT EXISTS` on PostgreSQL), changed only by
  the new conditional `IEmailMessageLedger.transitionApproval`. `transition()` leaves them alone, so a deferral, a
  retry or a claim no longer erases an approval (it lived in `lastError` before). On re-fetch the poll asks
  `MailboxSession.findByMessageId` for the candidate with that hash, then the one at the row's `sourceRef`; the IMAP
  session reads every exact hit in the polled and the move folder. A mail that does not hash as the held one waits for
  approval again.
- **Held starts are always settled.** `EmailReplySender.settleHeldStart` decides a held start from its conversation
  (the mail step's `hitlDecision`, then the state): approved → `START_APPROVED`, back to `RECEIVED`; rejected →
  `REJECTED` and `rejectedReply`; approved but ERROR → `email_gate:start_failed`; no verdict →
  `email_gate:start_cancelled`; gone → `email_gate:conversation_gone`. The resume observer calls it in either mode —
  trigger mode never located a row before, so an approved start there was stranded. `EmailInboundSweeper` reconciles
  `AWAITING_START` rows the observer missed (`findByApprovalState`), looking again every `stranded-after` while the
  pause is pending.
- **The approver sees the mail.** The held start's context carried the mail without its text or attachments, and the
  simple snapshot does not show context. The gate's context value now carries a view of the held mail (sender,
  authentication, subject, attachment names, the first 2,000 characters) and
  `ConversationHitlService.addHeldMailContext` puts it, redacted, first into the pause's approval context — what the
  Manager's Approvals page and banner, `get_approval_status` and Slack show. Never into the pause reason, which reaches
  the audit ledger; `HitlEmailNotifier`'s approver mail still carries only the reason.
- **Smaller ones.** A gate value that cannot be read fails closed (`EmailApprovalGate.parse`). A rejected held start
  ends its conversation (marker `emailGate:preRun = "start"`), so the next mail starts a fresh one. Saving warns
  `email.read-only-everything` for a `readOnlyTools` pattern that matches every tool (Manager toast, 11 locales).
  `discover_tools` / `fetch_tool_response_page` are exempt only with source `builtin`. Rule-driven httpcall paths are
  normalised by `HttpCallToolsProvider.normalizeEndpointPath`. `eddi.email.approval-gate.expiry` is a
  `@ConfigProperty` on `ConversationService`. Operator prompt updated (revision bump).

### Design decisions

- The recorded gate is the latest mail's, not a merge: a target's policy can change between mails, and the newest
  is what the admin set last.
- On a content mismatch the mail is held again rather than refused: it is from the same (claimed) sender, and the
  approver now sees what it says.
- Legacy mail conversations (from before the gate was recorded) fail closed until their next mail records one.

### Tests

`EmailApprovalGateTest`, `ConversationEmailPreRunTest` (continuation and check-back), `EmailInboundProcessorApprovalGateTest`
(content binding, deferral), `EmailReplySenderApprovalGateTest`, `EmailInboundSweeperTest`,
`ConversationHitlServiceEmailGateTimeoutTest`, the ledger contract (`approvalHold`, MongoDB and PostgreSQL ITs), and
`EmailApprovalGatePersistenceTest` (step context and the recorded gate through both stores).
