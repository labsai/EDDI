## Email thread replies: late joiners and unanswered mails (2026-11-23)

**Repo:** EDDI · **Branch:** `feat/email-thread-replies` (PR #1524) · **Plan:** email v2 §9.3 and §9.4, as amended on #1486

**What changed:**

- **A `Cc` is copied only if they were sent every earlier reply** (`EmailReplyAudience.readEveryReply`, a predicate the ledger supplies to `compute` and `decide`, applied to the pre-turn `mail.audience` and every send attempt). A person added to the thread halfway no longer gets an answer that draws on mails sent before they joined; the drop is counted as `late_joiner`. A conversation with no sent reply admits everyone; an unreadable ledger drops all copies.
- **Continuing a thread conversation under `anyAuthenticatedParticipant` is measured against every mail that ran a turn**, not only the sent replies. A private mail whose reply is still queued, refused, failed or never produced is in the history the agent reads, so a participant who was not sent a reply to it forks. It fails closed: after an unanswered mail, nobody continues that conversation.
- Tests: `EmailReplyAudienceTest` (predicate and ledger-backed cases), `EmailThreadRepliesTest` (`lateJoinerIsNotCopied`, `unsentPrivateMailForks`). Docs: [`email-integration.md`](../email-integration.md).

**Review fixes (CodeRabbit, same day):**

- A relay's `552` on a `Cc` (filed by Angus among the valid-but-unsent) is now read from the exception chain, so a refused copy is dropped and the reply resent instead of failing whole (`SmtpSubmitter.rejectedRecipients`).
- Copies whose address is not ASCII are never copied: the outbound renderer cannot send them, and one would have failed the whole reply.
- A conversation that runs as a thread identity keeps the every-reply rule when `replyAudience` is later switched back to `sender` (`participantOf` reads the conversation's own identity; an unreadable conversation counts as one).
- MongoDB: a write-concern error on the recipient insert is rethrown; a bounced `Cc` is suppressed before its record is removed; a dropped erasure audit entry is logged.
- Manager copy: the reply-recipient hint names the `Reply-To` case, the thread warning limits "no memory" to earlier conversations and says what the shared thread still carries, and conversation mode with a thread audience has its own note (`emailChannel.modeNoteConversationThread`, 11 locales).
- **Continuing a thread conversation is now decided per turn**: every inbound mail that ran a turn needs a sent reply to the sender (`IEmailMessageLedger.turnMailIds` / `answeredMailIdsSentTo`, in all three stores), so a pause notice plus its resume reply no longer stand in for a later unanswered private turn. The ledger predicate also drops a copy, instead of throwing, when its per-address read fails.
- *Known gaps left open on the PR:* erasing a directory principal does not reach its shared thread conversations; the export reads reply text by step position; thread conversations can outlive the ledger rows that name them (retention choice for the maintainer).
