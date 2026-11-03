## 🔒 fix(engine): agent-findings sweep — security, runtime, HITL, GDPR and cluster (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-engine-security-runtime`)

### What changed and why

Verified the 55 CodeRabbit agent findings filed against `engine/{security,runtime,hitl,setup,tenancy,schedule,triggermanagement,cluster,gdpr}`
against current `main`: 15 fixed here, 25 already fixed by earlier PRs (#1187, #1197, #1198), 4 accepted risks
(documented behaviour or operator-only configuration), 11 left for a decision (contract or cross-area changes, one already on
the unmerged #1234). Plus the CodeRabbit outside-diff finding on #1208.

- **NATS TLS** — a `tls://` / `wss://` URL turned TLS on inside jnats with `SSLContext.getDefault()`, skipping the host-name
  check that `eddi.nats.tls.enabled` installs; the verifying context is now built for those URLs too. The
  `createSocket(Socket, InputStream, boolean)` overload of `HostnameVerifyingSslContext` now returns a verifying socket like
  every other overload.
- **HITL** — the batch timeout policy no longer treats a rule shadowed by an earlier rule with the same match as speaking for
  a call (a policy-less task rule could let another call's `AUTO_APPROVE` decide the whole batch). The group pending-approval
  retention sweep cancels only the pause it found expired (`cancelPausedDiscussion` with the pause id), not a discussion an
  approval resumed in between. The self-conversation check sees JSON-escaped ids. The self-ungating guard refuses a patch
  whose earlier add/remove shifts a gated task's index, and an MCP `update_resource` that replaces a gated LLM document.
- **Sharing** — revoking the canonical subject `describe()` returned no longer re-encodes it (a `%`/`|` in a name made the
  revoke match nothing and report success); the share dialog's grant list follows the same structural disclosure rule as
  descriptor responses when workspaces are not enforced.
- **GDPR** — a failed dead-letter, journal, descriptor or checkpoint delete keeps the conversation snapshots for the retry,
  as a failed attachment or vault sweep already did.
- **Runtime** — the secret-context scrub leaves spend and token-usage records alone (a secret equal to the turn's cost
  erased its metering); a later workflow no longer puts back input a `scope: secret` instruction scrubbed; Dream keeps global
  and reader-less group memories apart; a 3-segment DELETE marks a descriptor deleted only when it is that descriptor's own
  resource (a `/spacestore/variables/{key}` delete could hide another owner's configuration).
- **Unauthenticated guard** — the side-effecting managed-conversation GET is recognised under a `quarkus.http.root-path`.

### Decisions left open

Conversation review after a compatible update, sharing CAS, setup-api `apiAuth` allowedHosts doc/code mismatch, checkpoint
writes racing an erasure, journal tombstone retention, tool-pause narration vs output guardrails, team-cadence mutation by a
former member, cascade ids of non-viewable nodes, team-share notifications to former members — listed in the batch report.

**Files:** [`NatsConnectionManager.java`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java),
[`HostnameVerifyingSslContext.java`](../../src/main/java/ai/labs/eddi/engine/cluster/HostnameVerifyingSslContext.java),
[`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java),
[`HitlCrashRecoveryObserver.java`](../../src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java),
[`ApprovedCallScope.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/ApprovedCallScope.java),
[`SelfUngatingGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/SelfUngatingGuard.java),
[`RestResourceSharing.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/rest/RestResourceSharing.java),
[`ResourceSharingService.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`ResourceAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceAccessGuard.java),
[`UnauthenticatedRequestGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuard.java),
[`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java),
[`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
[`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java),
[`DocumentDescriptorFilter.java`](../../src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java),
[`gdpr-compliance.md`](../gdpr-compliance.md)
