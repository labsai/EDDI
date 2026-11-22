## 🔒 feat(email): reply review — a person approves, edits or rejects a reply before it leaves (2026-11-22)

**Repo:** EDDI (`feat/email-reply-review`, on top of `feat/email-approval-gate` merged with `feat/email-smime-backend`)
— phase C4 part 2 (§8.2) of the email channel v3 control plan.

### What changed and why

An email agent answers people outside the organisation, and some answers should not leave on the model's word alone.
A conversation-mode email integration now takes `emailControl.replyReview` — `never` (the default, so upgrading
changes nothing), `always`, `firstContact` (the reply to a sender's first mail to the target, the same first-contact
test as `approvalPolicy`) or `external` (a reply whose recipient, as `EmailReplyRecipients` chooses it, is outside
`emailControl.internalDomains`: `contoso.com` is that domain, `*.contoso.com` its subdomains; none listed means every
reply).

- **Config** (`EmailControl`, `EmailChannelSettings`). Both fields live in the typed `emailControl` object, so both
  stores persist them with the integration. `copy()`, `equals` and `hashCode` know them — the targeted sender-list
  update copies `emailControl`, and a copy that dropped `replyReview` would have switched the review off. A stored
  mode EDDI does not know reads as `always`; a REST or MCP write with one is a 400. Refused in trigger mode (it never
  replies) and with a GROUP target (`RestChannelIntegrationStore`: a group's answer has no turn that could wait).
  Save warning `email.reply-review-no-internal-domains`.
- **Armed per mail** (`EmailInboundProcessor.replyReviewOf`). Only the mail's own turn carries the new reserved
  context key `emailReplyReview` (`EmailReplyReview.CONTEXT_KEY`, added to `ClientContextGuard.RESERVED_KEYS`), and it
  counts only on a turn tainted `origin=email`. A value of the wrong shape still arms the review.
- **The pause** (`Conversation.pauseForReplyReviewIfAsked`). After the turn's whole pipeline — on the say path, after
  a resume that ran the rest of the turn (an approved tool call, an approved pre-run hold), and after a revision — an
  armed turn with something to send pauses as a RULE pause after the last workflow. The step keeps the draft
  (`emailGate:replyReview`: the whole text the reply would carry, `CONVERSATION_START`'s output included for a first
  mail) and the conversation output `emailReplyReview: pending`. A turn that ends its conversation
  (`CONVERSATION_END`, `STOP_CONVERSATION`) waits for its review first; the decision ends the conversation —
  `checkActionsForConversationEnd` skips while a review is pending, which would otherwise have left an undecidable
  pause and dropped the reply. MCP `get_approval_status` reports `replyReview: true`.
- **The decision** (`Conversation.decideReplyReview`) runs no task again: `approved`, `edited` (the approver's text in
  the conversation output `emailReplyText`; the step's `emailGate:replyReviewDecision` keeps digests and sizes) or
  `rejected`. It goes through the usual resume — REST `POST /agents/{conversationId}/resume`, MCP
  `resume_conversation`, the Manager — so no new endpoint or tool; a mail-started turn is refused both by
  `EscalationGuard` as before.
- **HITL side.** `HitlReviewDetails` serves the whole reply as `pauseDetails.editableAnswer` with
  `pauseDetails.replyReview: true`, on every agent; `HitlDecisionValidator` accepts the edit regardless of the
  agent's `allowAnswerEdits` (not blank, at most `max(maxEditChars, draft + 2000)`, no leftover redaction
  placeholder); `ruleDraftOf` gives the audit the reply as the draft; the approval context's first item `Reply` shows
  it redacted; `applyEmailGateTimeout` makes the review `AUTO_REJECT` after `eddi.email.approval-gate.expiry`, also
  under `approvalPolicy: none` and an agent `AUTO_APPROVE`.
- **Reply side** (`EmailReplySender`). `reviewedReplyText` is the one place that decides what a reply carries: an
  unarmed turn sends what it wrote; an armed one only after approval — the edited text when there is one — on the
  first attempt, every retry and after an S/MIME hold (the REPLY row re-reads the step). The approved reply is a
  `REPLY` keyed by the mail's step, so the resume observer, the turn callback and the stranded sweep send it once.
  While the review waits nothing goes out — the in-thread pause notice would have carried the draft; any other pause
  of an armed turn sends the notice without the agent's output; a rejection or expiry sends nothing and notes the
  mail's row `email_gate:reply_rejected` (not a contact for `firstContact`).
- **Manager.** Channel detail: a "Reply review" section (mode, internal domains). Approvals page and banner: a notice
  that the pause holds an email reply; the existing answer editor does the editing. 11 locales.
- **Operator.** Prompt learns reply review; revision 86.
- **Docs.** `email-integration.md` "Reply review", `hitl.md` "Email conversations", `hitl-review-and-audit.md`.

### Design decisions

- **The edit replaces the whole reply, not the LLM task's output in place.** The plan names
  `hitlConfig.review.allowAnswerEdits` / `editedAnswer`. The `editedAnswer` field, its validation and the
  `editableAnswer` view are reused, but the in-place LLM edit (`ReviewerEditStep`) is not: what is mailed is
  `replyText` — several outputs, for a first mail the start turn's too — so an in-place edit of one of them would have
  shown the approver a draft other than what is sent, worked only for agents that opted into `allowAnswerEdits`, and
  not at all for rule-only agents. Consequence: the conversation keeps the draft as the turn's output and later model
  calls see it as before; output guardrails do not screen the edit.
- **Rejection sends nothing**, like an expiry — the plan says so for expiry; a human rejection sending the generic
  "a reviewer declined" text would tell an outside sender that a reply was held.
- No new ledger state, no new REST or MCP surface.

### Not done

- No live run against the test mailbox yet (§13 asks for one before relying on it).
- Output guardrails do not screen an approver's edited reply.
