## 📝 docs(planning): email channel v3 — control: sender lists over MCP, held mail, approvals, roles, send-out, S/MIME (2026-11-18)

**Repo:** EDDI (`docs/email-channel-v3-control-plan`, stacked on `docs/email-channel-v2-plan`)

### What changed and why

A plan only; no code changes. [`planning/email-channel-v3-control-plan.md`](../../planning/email-channel-v3-control-plan.md) makes the email channel rich and admin-controlled, after a live test against a Google Workspace mailbox on 2026-11-18: a mail from an unknown sender was dropped with only a log row, and nothing could be decided about it. A review against the code the same day reshaped it:

- **Storage:** nested settings need a typed `emailControl` object, because `platformConfig` is a flat string map.
- **Held mail:** it must be a settled state that does not pin the cursor.
- **Forged mail:** a forged From is refused as `not_allowlisted` before authentication, so the verdict has to be stored.
- **The approval gate:** the email gate cannot be merged into the agent's `toolApprovals`, because exemptions always win.
- **Send-out recipients:** a recipient must never run in the requester's conversation.

Seven phases:

- **C1 — Sender lists:** editable over REST and MCP (`update_email_senders`, expected version, audit). An `origin=email` taint makes the runtime refuse any change an email-started turn tries to make to its own integration or approvals.
- **C2 — S/MIME:** signing on by default (`emailControl.smime.sign: true | required | false`), with the certificate held on a `SMIME_SIGNING` connection. A sending-health self-test names a provider that does not DKIM-sign for the domain.
- **C3 — Held mail:** refused mail can be held for review instead of dropped. The reviewer can discard it, run it once, or run it and allow the sender; "run and allow" is offered only for an aligned pass and needs EDIT. A released unauthenticated mail runs memory-free with read-only tools and causes no outgoing mail.
- **C4 — Approval before acting:** a per-target `approvalPolicy` as its own gate after `TaskToolApprovalsResolver`, plus reply review as an end-of-turn rule pause with edited answers.
- **C5 — Sender roles:** roles can only narrow what applies; this phase depends on #1394's replay protection.
- **C6 — Send-out:** recipients come from `outboundRecipients`, every external send is approved, and the approval is bound to a hash of recipient, subject and body. The recipient's answer opens a separate, memory-free conversation without `sendMail`.
- **C7 — Mail control:** one Manager tab per integration, with full MCP parity.

[`planning/email-channel-plan.md`](../../planning/email-channel-plan.md) §22 and [`planning/email-channel-v2-plan.md`](../../planning/email-channel-v2-plan.md) §2.3 and §11 carry "Amended by v3" notes.

```decision-log
| 2026-11-18 | Mail an email integration sends through its SMTP is S/MIME-signed by default (`emailControl.smime.sign: true`); `required` refuses to send unsigned, `false` switches it off; the certificate is a credential on a `SMIME_SIGNING` connection | Product owner: EDDI's mail must be verifiably EDDI's; DKIM is the provider's and was found inactive for a real domain | Signing off by default; DKIM only; the certificate in the integration settings |
| 2026-11-18 | v2 §2.3 "No LLM-chosen recipients" narrowed: a send-out agent may mail a recipient from an admin-controlled set (`outboundRecipients`), each external send approved and bound to recipient, subject and body, only for senders whose role allows it | Product owner wants "Cc EDDI, send customer xyz the offer"; v1 §22 already allowed it as an allowlisted, approval-gated item | Keeping the ban; a free-form `sendEmail` tool; approving only the first send to a recipient |
| 2026-11-18 | Refused mail can be held for human review (discard / run once / run and allow); a released mail without an aligned DKIM pass runs memory-free with read-only tools and never causes outgoing mail | Product owner wants to hear about and decide on mail EDDI does not accept; a forged From must not get a reply | Dropping with a log row only; approve by replying to a notice |
| 2026-11-18 | The email approval gate is a separate gate applied after `TaskToolApprovalsResolver`, not a merge into the agent's `toolApprovals` | Agent `exempt` entries, `task-approvals.mode=replace` and `AUTO_APPROVE` timeouts would silently undo a merged rule | Merging `requireApproval: ["*"]` into the agent block |
```

### What's next

C1 and C2 can start at once; C3 and C4 after C1; C5 and C6 wait for #1394 (see the plan's §12). Four open questions are in its §15.
