## 📝 docs(planning): email channel v3 — control: sender lists over MCP, held mail, approvals, roles, send-out, S/MIME (2026-11-18)

**Repo:** EDDI (`docs/email-channel-v3-control-plan`, stacked on `docs/email-channel-v2-plan`)

### What changed and why

A plan only; no code changes. [`planning/email-channel-v3-control-plan.md`](../../planning/email-channel-v3-control-plan.md) makes the email channel rich and admin-controlled, after a live test against a Google Workspace mailbox on 2026-11-18: a mail from an unknown sender was dropped with only a log row, and nothing could be decided about it.

Seven phases:

- **C1 — Sender lists:** editable as entries over REST and MCP (`update_email_senders`, with an expected version and an audit entry). A mail-triggered agent can never hold these tools.
- **C2 — S/MIME signing:** on by default (`smimeSign: true | required | false`), plus a sending-health self-test that names a provider not signing DKIM for the domain.
- **C3 — Held mail:** refused mail can be held for review instead of dropped (`onRefused`). The reviewer can discard it, run it once, or run it and allow the sender. A preview is fetched live from the mailbox, and an unauthenticated mail never gets a reply.
- **C4 — Approval before acting:** a per-target `approvalPolicy` (`actions`, `firstContact`, `everyTurn`, `none`) on the existing HITL machinery, plus reply review that can edit the reply before it goes out.
- **C5 — Sender roles:** a role per sender that can only narrow which targets, tools, approvals and caps apply.
- **C6 — Send-out:** a narrowly scoped send-out agent mails third parties. Recipients come from an admin-controlled set, every new recipient is approved, and the recipient can answer in a thread-scoped conversation.
- **C7 — Mail control:** one Manager tab per integration, with every value reachable over MCP.

```decision-log
| 2026-11-18 | Outgoing mail of an email integration is S/MIME-signed by default (`smimeSign: true`); `required` refuses to send unsigned, `false` switches it off | Product owner: EDDI's mail must be verifiably EDDI's; DKIM is the provider's and was found inactive for a real domain | Signing off by default; DKIM only |
| 2026-11-18 | v2 §2.3 "No LLM-chosen recipients" narrowed: a send-out agent may mail a recipient from an admin-controlled set (`outboundRecipients`), every new recipient approved, only for senders whose role allows it | Product owner wants "Cc EDDI, send customer xyz the offer"; v1 §22 already allowed it as an allowlisted, approval-gated item | Keeping the ban; a free-form `sendEmail` tool |
| 2026-11-18 | Refused mail can be held for human review (discard / run once / run and allow); a released unauthenticated mail runs memory-free and never causes outgoing mail | Product owner wants to hear about and decide on mail EDDI does not accept; a forged From must not get a reply | Dropping with a log row only; approve by replying to a notice |
```

### What's next

C1 and C2 can start at once; C3 to C6 follow (see the plan's §11). Four open questions are in the plan's §14 (linking senders to EDDI users, certificate scope, S/MIME for the system mailer, the default for held mail).
