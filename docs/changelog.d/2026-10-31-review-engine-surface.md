## 🔒 fix(engine-surface): review findings on channels, Slack, MCP, A2A, sharing and outbound HTTP (2026-10-31)

**Repo:** EDDI (`fix/review-engine-surface`)

Fixes from the CodeRabbit review and the full code review (revision 01c3250) for
the external surfaces: `integrations/**`, `engine/mcp`, `engine/security`,
`engine/a2a`, `engine/httpclient`.

### Channels and Slack

- **Only credentials are vault-resolved.** `ChannelTargetRouter` used to expand
  `${vault:...}` in every `platformConfig` value, so an editor could put a
  restricted reference into a webhook's `inputTemplate` and read the plaintext in
  the resulting conversation. Only `botToken` and `signingSecret` are resolved now;
  everything else stays literal.
- **An unresolved credential is dropped, for every channel type.** The resolver
  passes an unresolvable reference through unchanged, and Slack used that public
  text as its HMAC key. The router now drops it (as it already did for webhooks),
  and `SlackSignatureVerifier` refuses a reference-shaped key outright.
- **A Slack bot token honours its secret's `allowedHosts`** (checked against
  `https://slack.com/api/` via `VaultHostAllowlist`).
- **The approval card no longer borrows another integration's bot token** via the
  editor-chosen approval channel; resume outcomes are posted only for
  conversations owned by a Slack principal (`SlackUserIdentity.isSlackPrincipal`),
  so a hand-made `channel:slack:...` trigger intent cannot post with another
  integration's identity. Only the reserved `slack:` form counts: a raw
  `U123ABC`-shaped owner is just a shape an OIDC principal can share, so a legacy
  thread mapping gets resume posts again once its next message re-keys it.
- **Event user ids are validated** (`[UW][A-Z0-9]+`) before they become
  `slack:<team>:<user>`, so a teamless signer cannot alias a workspace-qualified
  user by sending `user: "T1:U2"`.
- **Approval-card text is mrkdwn-escaped** (tool previews, reason, agent label).
- **`/v1` matrix parameters** (`v1;x/chat/completions`) no longer skip
  `OpenAiAuthFilter`'s key check.

### MCP

- `chat_managed` re-checks USE on the retained conversation's own agent, and a
  refusal there is the neutral "you may not continue this conversation" (the
  owner does own it), not "you do not own this managed conversation".
- `submit_group_human_input` (and REST `POST .../human-input`) return an
  acknowledgement unless the caller is the owner or an admin. The Manager's
  `submitHumanInput` resolves to `GroupConversation | HumanInputAcknowledgement`
  (narrow with `isHumanInputAcknowledgement`).
- `McpAdminTools` no longer echoes raw exception text: caller-fixable failures are
  described, everything else returns a log reference (`toolFailure` overload).
  Two refusals stay caller errors rather than `INTERNAL`: a guard's Quarkus
  `ForbiddenException` (`ResourceAccessGuard`, `OwnershipValidator`) answers
  errorCode `FORBIDDEN` with its reason, and malformed JSON in a `config` /
  `resourceMappings` argument answers "config is not valid JSON (line L, column
  C)" (`McpToolUtils.readCallerJson`) — resources, channels and triggers alike.
  `list_agent_resources` reads each distinct workflow once.
- `McpApiToolBuilder`: spec parameter names that collide with template namespaces
  are renamed (`properties_2`); suffix allocation is linear; a spec server URL is
  literal (declared variables take their defaults, other `{...}` is refused).
- `OpenApiSpecFetcher` repeats the target-policy check after connecting (DNS
  rebinding) and discards the response if it now fails.

### Security, A2A, outbound HTTP

- With authentication off, `GET /agents/managed/...` (creates a conversation)
  gets the same cross-site check as a `POST`.
- Access requests answer a non-configuration id exactly like a missing one, after
  counting the attempt; a cascade skips `HUMAN` group members, budgets its
  seeding, and omits the name of a skipped resource the caller cannot view.
- A2A caps caller-chosen ids at 256 characters and refuses non-scalar JSON-RPC ids.
  Every JSON-RPC response now carries the `id` member, `"id": null` for an
  unreadable request, as JSON-RPC 2.0 requires (`JsonRpcResponse` was NON_NULL).
- httpcalls: a cross-origin redirect hop keeps only content-negotiation headers
  (a credential can sit in any header name); with SSRF protection off a redirect
  hop's *connected* peer is held to the previous hop's address class.
- Constant-time compares in the area now use `HmacSignatures.constantTimeEquals`.

### Deferred

- Review eligibility pinned to the conversation's start version
  (`ConversationAccessGuard.requireConversationRead`) needs a descriptor field
  written by conversation setup (engine/internal) — left for that owner.
- Former team members' share notifications need the share subject stored on the
  notification (store schema change) for a cheap read-time check.
- Control-character scrubbers and domain normalisers (§6) live outside this area.

**Files:** [`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`SlackSignatureVerifier.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackSignatureVerifier.java),
[`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackHitlResumeObserver.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlResumeObserver.java),
[`SlackUserIdentity.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackUserIdentity.java),
[`SlackHitlSupport.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java),
[`OpenAiAuthFilter.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java),
[`McpConversationTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`McpHitlTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`McpAdminTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java),
[`McpApiToolBuilder.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java),
[`OpenApiSpecFetcher.java`](../../src/main/java/ai/labs/eddi/engine/mcp/OpenApiSpecFetcher.java),
[`UnauthenticatedRequestGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuard.java),
[`ResourceSharingService.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`ConfigGraphResolver.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ConfigGraphResolver.java),
[`WorkspaceNotifications.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/notifications/WorkspaceNotifications.java),
[`A2AWireFormat.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2AWireFormat.java),
[`A2AModels.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2AModels.java),
[`McpToolUtils.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java),
[`HttpClientModule.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java);
docs: [`slack-integration.md`](../slack-integration.md), [`webhook-triggers.md`](../webhook-triggers.md),
[`httpcalls.md`](../httpcalls.md), [`security.md`](../security.md), [`a2a-protocol.md`](../a2a-protocol.md),
[`workspaces.md`](../workspaces.md), [`group-conversations.md`](../group-conversations.md), [`architecture.md`](../architecture.md).
