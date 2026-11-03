## 🐛 fix(integrations): close six channel findings from the agent-findings review (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-integrations`)

### What changed and why

A batch of 23 scanner findings against `integrations/{channels,email,slack,webhook}` was checked against the
current code. Most had been closed since the scans (#1126, #1187, #1175) or are documented limitations; six
were real and are fixed here:

- **Slack bot token, composite references.** The `allowedHosts` check on a Slack `botToken` parsed only the
  first `${vault:…}` in the value, while the resolver expands all of them. Every reference is now checked
  (`ChannelTargetRouter.botTokenMayGoToSlack`, which the legacy connector path shares).
- **Slack approval card without a known pause.** A card built before its pause could be read was recorded with
  no pause identity and matched any pause that began before the record was written — so a delayed card could
  approve a later pause it does not show. Such a card is now posted without buttons and not recorded, and a
  record without a pause epoch matches nothing (`ISlackApprovalRecordStore.matchesPause`, `notifyApprovers`,
  the group listener). The next message in the paused thread posts a card with buttons
  ([hitl.md](../hitl.md)).
- **Slack pause notice escaping.** The in-thread pause notice appended the pause reason as raw mrkdwn; it is
  escaped like the approval card's reason.
- **Email, duplicate Message-ID.** A mail from another sender carrying the Message-ID of a mail waiting for its
  retry took over that mail's ledger row and the conversation it names. It now waits, and is settled as a
  duplicate once the real mail has run (`EmailInboundProcessor`).
- **Email, group reply retries.** A cached group reply was retried to its stored recipient without the recipient
  rules being applied again, so turning `honorReplyTo` off did not stop a pending reply to a `Reply-To`. The
  retry now decides again, as every other reply kind does ([email-integration.md](../email-integration.md)
  already promised this).
- **Legacy Slack connector `groupId`.** A `channels[].config.groupId` on an agent became a standing route into
  that group once deployed, with no check that the deployer may use it. `deployAgent` now requires USE on it,
  the rule the channel API applies to a group target ([slack-integration.md](../slack-integration.md)).

Verdicts: 6 fixed, 7 already fixed, 1 false positive, 4 accepted risk, 5 need a decision (Slack approval records
keyed by the mutable integration name; editor-declared Slack `teamId` as the identity namespace; space
membership for channel credential references; IDNA deviation characters in the notify recipient policy;
discovery records during a partial email GDPR erasure).

**Files:** [`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackGroupDiscussionListener.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java),
[`ISlackApprovalRecordStore.java`](../../src/main/java/ai/labs/eddi/integrations/slack/hitl/ISlackApprovalRecordStore.java),
[`EmailInboundProcessor.java`](../../src/main/java/ai/labs/eddi/integrations/email/EmailInboundProcessor.java),
[`EmailReplySender.java`](../../src/main/java/ai/labs/eddi/integrations/email/reply/EmailReplySender.java),
[`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java)
