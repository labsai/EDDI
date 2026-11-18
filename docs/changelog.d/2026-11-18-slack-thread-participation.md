## fix(slack): the bot answers only in threads it takes part in; one mention rule (2026-11-18)

**Repo:** EDDI (`fix/slack-thread-participation`)

### What changed and why

Findings G1, G3, G4, G5 of the November 2026 code review (live: SL1, SL2). With `message.channels`
subscribed, which the setup guide asks for, Slack sends every message of a channel, and the handler
answered a reply in a thread it had never joined: with no thread lock the reply fell through to the
integration's default target, so private team talk reached the model at the agent's cost, and the
bot then locked the thread and answered every later reply in it. A message that mentioned the bot
anywhere but at the start got two answers, one for the `message` event and one for `app_mention`,
and a reply that opened with another person's mention was dropped.

- **Participation rule.** An unmentioned thread reply is answered only when the bot already takes
  part in the thread: the thread has a target lock, it is under a group discussion's agent message,
  or the replying user has a conversation mapping for one of the channel's targets in that thread
  (`SlackEventHandler.participatesInThread`). The mapping is in the database, so it survives a
  restart, which the in-memory lock does not on a single node; a mapping that is found writes the
  lock back. Otherwise the reply is dropped before routing — no turn, no model call — and counted in
  `eddi.slack.thread.replies.ignored`. An unmentioned top-level channel message was already ignored
  (unless an observer is configured) and is pinned by a test; direct messages are unchanged.
- **One mention predicate.** The thread-reply branch uses `mentionsThisBot(text, botUserId)`, the
  observer path's test: the bot's own user id, anywhere in the text. A leading mention of another
  user is no longer taken for the bot's, and `stripBotMention(text, botUserId)` strips only the
  bot's own leading mention. A DM is exempt from the test (Slack sends no `app_mention` for it).
  As a net under it, a processed message is also marked by `channel + ts`, so the `message` and
  `app_mention` events of one Slack message yield one turn in either order even when the envelope
  names no bot id.
- **Metric and dashboard.** `eddi.slack.thread.replies.ignored` (documented in `docs/metrics.md`),
  one panel on the Full Metrics Reference dashboard and its Kubernetes copy.

- **Direct messages on the error, help and resume paths (G3).** A DM's channel id belongs to no
  integration, so `getBotToken` found nothing for it and the timeout/error notice, the help text and
  the continuation after an approval were never sent. The handler's two `postHelp` calls and the
  error notice now take the token of the app that signed the event
  (`ChannelTargetRouter.getBotTokenForSigner`, by integration name or legacy signing secret). The
  resume observer, which has no event, takes the token of the one app serving the thread's agent
  (`getBotTokenForTarget`, narrowed by the workspace in the user id); two apps with different
  tokens serving one agent is ambiguous and posts nothing, and a channel an integration owns but
  left tokenless never borrows another app's token.

- **Configurable Slack API base URL (G5).** `SlackWebApiClient` hard-coded `https://slack.com/api/`,
  so no test could run a successful Slack call. `eddi.slack.api-base-url` (default unchanged) is
  validated when `SlackConfig` is created at startup: `https`, or `http` to a literal loopback
  address, no credentials, query or fragment; a bad value stops the startup with a message that
  names the property and never echoes the value. The request keeps the same outbound policy
  (`Profile.DEFAULT`, no redirect followed with the token). `FakeSlackApi` is an in-process
  `OutboundEngine` behind the real facade, so `SlackWithFakeSlackApiTest` is a plain unit test and
  needs no socket. The vault `allowedHosts` check for a vaulted bot token still judges slack.com.

**Files:** [`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackThreadParticipationTest.java`](../../src/test/java/ai/labs/eddi/integrations/slack/SlackThreadParticipationTest.java),
[`slack-integration.md`](../slack-integration.md), [`metrics.md`](../metrics.md)

### Behaviour changes an upgrader will notice

- Replies in threads between people no longer get an answer. A thread the bot was mentioned in or
  has spoken in keeps working.
- After a restart of a single node, a thread the bot was in answers again for those who had already
  talked to it there; someone new must mention the bot once.
- A reply that starts with another person's mention is now answered when the bot is in the thread
  (before, it was dropped).
