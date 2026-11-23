## Email thread replies: late joiners and unanswered mails (2026-11-23)

**Repo:** EDDI · **Branch:** `feat/email-thread-replies` (PR #1524) · **Plan:** email v2 §9.3 and §9.4, as amended on #1486

**What changed:**

- **A `Cc` is copied only if they were sent every earlier reply** (`EmailReplyAudience.readEveryReply`, a predicate the ledger supplies to `compute` and `decide`, applied to the pre-turn `mail.audience` and every send attempt). A person added to the thread halfway no longer gets an answer that draws on mails sent before they joined; the drop is counted as `late_joiner`. A conversation with no sent reply admits everyone; an unreadable ledger drops all copies.
- **Continuing a thread conversation under `anyAuthenticatedParticipant` is measured against every mail that ran a turn**, not only the sent replies. A private mail whose reply is still queued, refused, failed or never produced is in the history the agent reads, so a participant who was not sent a reply to it forks. It fails closed: after an unanswered mail, nobody continues that conversation.
- Tests: `EmailReplyAudienceTest` (predicate and ledger-backed cases), `EmailThreadRepliesTest` (`lateJoinerIsNotCopied`, `unsentPrivateMailForks`). Docs: [`email-integration.md`](../email-integration.md).
