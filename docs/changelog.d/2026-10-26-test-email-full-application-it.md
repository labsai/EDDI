## 🧪 test(email): full-application IT for the email channel (2026-10-26)

**Repo:** EDDI (`feat/email-conversation-mode`)

The existing email ITs (`EmailImapPollingIT`, `EmailConversationModeIT`) wire the poller, processor and reply sender
by hand around a mocked conversation engine. `EmailFullApplicationIT` boots the real application (`@QuarkusTest`,
MongoDB Dev Services) and drives it through its REST API, with GreenMail as IMAPS and SMTPS server and a
deterministic agent (dictionary, behavior rules, output — no LLM):

- **Trigger mode** — `POST /channelstore/channels` answers `X-Eddi-Warnings: email.open-trigger` and creates
  `email-poll:<id>`; the poll is fired through `POST /schedulestore/schedules/{id}/fire` (claimed, then
  `ScheduleFireExecutor`); the mail runs a real turn with the mail as input and the `mail` context, its PDF lands in
  the attachment store, the ledger row ends `DONE`, the mail is moved, and nothing is sent. An
  `Auto-Submitted: auto-replied` mail is refused without a conversation.
- **Conversation mode** — DKIM-signed mail (real `DkimVerifier`/`SenderAuthenticator`) is answered in the thread
  (`In-Reply-To`, `References`, `Auto-Submitted`, AI disclosure); the answer to EDDI's Message-ID continues the
  conversation, another sender quoting it gets their own, unsigned and body-tampered mail is refused unanswered.
- **HITL in the thread** — a `PAUSE_CONVERSATION` turn sends the in-thread notice with the pause reason;
  `POST /agents/{conversationId}/resume` sends the continuation.
- **Bounce** — a permanent DSN for a reply lists the address under `GET /channelstore/channels/{id}/email-suppressions`
  (hashed) and the sender's next reply row ends `SUPPRESSED`.

Two test seams, enabled only by the IT's profile (`getEnabledAlternatives`), replace beans; no production code
changed:

- `E2eMailSocketConnector` (`engine.net`, test sources) runs the production author policy of `SafeSocketConnector`
  in full on every call, over an injected resolver that answers a public documentation address for
  `imap.mail.test` / `smtp.mail.test`; only an endpoint that passed is re-pinned to the loopback GreenMail port,
  keeping host name and TLS mode, so TLS and host-name verification run against a certificate for those names.
- `E2eMailDns` (`engine.mail`, test sources) is the production `MailDnsResolver` over an offline TXT source that
  publishes the `DkimTestSigner` key and a DMARC record.

Finding while writing it (no code change): the regular dictionary parser splits on spaces only, and the default
`inputTemplate` puts the body on the line after `-----`, so the body's first word is read as one token with the
separator (`instructions.\n-----\nhello,`). A dictionary agent on an email channel should not rely on the first word
of the body; LLM agents are unaffected.
