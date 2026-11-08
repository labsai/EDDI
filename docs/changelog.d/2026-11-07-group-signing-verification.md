## fix(signing): peer verification is fail-closed and binds the conversation, position and key version (2026-11-07)

**Repo:** EDDI (`fix/group-signing-verification`, on top of `feat/agent-signing-key-provisioning`)

### What changed and why

`security.requirePeerVerification` verified earlier speakers' signatures and then handed the
receiving agent the transcript anyway: every failure was a log line. A signature also proved less
than it seemed. It covered only the content and the phase name, so a signed entry copied into
another discussion, or into another phase of the same one, still verified; the verifier checked no
nonce, so a duplicated entry verified twice; and key validity windows were never enforced, so a
revoked key kept vouching for whatever it signed.

- **Fail-closed.** `GroupSigningGuard.visibleTo` gives one verdict per transcript entry
  (`VERIFIED`, `NOT_AGENT_AUTHORED`, `UNSIGNED`, `UNKNOWN_KEY`, `OUT_OF_WINDOW`, `BAD_SIGNATURE`,
  `DUPLICATE`). For a member that requires peer verification, every agent-authored entry that does
  not verify is replaced, in place, by a `[withheld: ... because ...]` placeholder before that
  member's phase prompt (`GroupContextBuilder.buildPhaseInput`, through the wired guard) and its
  `groupTranscript` context (`MemberTurnExecutor`) are built. In place, so the raw indices that
  window summaries are keyed on stay aligned; and a window summary that retells a withheld entry is
  replaced by the truncation marker for that member (a withheld entry the summarizer never reads,
  such as a facilitator's intervention, leaves the summary in place). If the member's own configuration cannot be
  read, it is treated as requiring verification. Each withheld entry is logged at ERROR the first
  time per conversation, then at DEBUG.
- **What the signature covers.** The payload is built in one place for signing and verification:
  the group conversation id, the phase index and name, the content, the speaker's display name,
  the target agent and the key version (the envelope's own `keyVersion` is outside its canonical
  form, so it goes in the payload). The entry type is not covered, so a reply the engine records as
  a `DISSENT`, or now a `BID` (`TaskForceEngine` used to drop the reply's signature there), keeps
  verifying.
- **Replay.** A nonce is accepted once per transcript; the first verified occurrence wins, so a
  forged copy cannot get the genuine entry withheld. An entry signed before its conversation began
  (beyond a 30 s clock-skew allowance) is withheld, so a revoked key cannot back-date entries into
  newer discussions.
- **Validity windows.** The signer signs with the newest declared key only while it is inside its
  `validFromMs`/`validUntilMs` window and does not fall back to an older key; the verifier rejects
  an entry signed outside the window of the key it names. Revoking a key: generate a new one, then
  set the old key's `validUntilMs`.
- **Signer.** `signOutgoingMessage(...)` became `sign(gc, entry)`. It no longer registers its own
  nonce with `NonceCacheService`: a freshly minted UUID can never collide, and with
  `eddi.cluster.degraded.nonces=reject` a NATS outage silently turned signing off. The nonce
  service is no longer injected into `GroupConversationService` (it stays for other replay checks).
  A signing agent that cannot sign (no declared key, key outside its window, vault failure,
  self-verify mismatch) now logs at ERROR, since peers that verify will withhold the entry.
- **Who must sign.** Only entries whose speaker is an agent in the agent store must verify. The
  user's question and follow-ups, a human member's turn, a reviewer's edit, the engine's own notes
  and a nested group's answer carry no signature and are shown as they are. They are not
  authenticated: whoever can write the group-conversation store can add one, or re-attribute an
  agent's entry to a non-agent speaker id, under any display name. Verification is keyed on the
  speaker id, while prompts show the display name; both docs say so.
- **Docs, Manager, operator.** [`architecture.md`](../architecture.md#cryptographic-agent-identity)
  (signing, replay, peer verification; the RFC 8785 claim corrected) and a new
  [Signed contributions](../group-conversations.md#signed-contributions) section with the known
  limits. The Manager's "Require peer verification" description no longer says failures are only
  logged (all 11 locales). The Platform Operator's prompt says what a verifying agent sees and how
  to revoke a key (operator revision bumped).

Nothing had been signed in the wild (provisioning only lands with the base branch), so the change
of the signed bytes needs no migration.

### Not filtered yet

The prompts the engine writes itself for role turns (convergence judge, facilitator briefing, vote
tie-break, dissent round, task-force plan, execute, verify and bid) quote the round just run, task
records or a short excerpt directly. Agent follow-up answers, facilitator interventions and the
reformatted task-force verification entry are recorded unsigned, so a verifying member sees them
withheld.

**Files:** [`GroupSigningGuard.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupSigningGuard.java),
[`GroupContextBuilder.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupContextBuilder.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`TaskForceEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`SignedEnvelope.java`](../../src/main/java/ai/labs/eddi/configs/agents/crypto/SignedEnvelope.java),
[`JacksonCanonicalizer.java`](../../src/main/java/ai/labs/eddi/configs/agents/crypto/JacksonCanonicalizer.java);
tests `GroupSigningRoundTripTest` (real Ed25519), `GroupSigningGuardTest`, `MemberTurnExecutorTest`,
`GroupContextBuilderWindowingTest`, `TaskForceEngineTest`.
