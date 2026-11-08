## fix(signing): peer verification is fail-closed and binds the conversation, position and key version (2026-11-08)

**Repo:** EDDI (`fix/group-signing-verification`, on top of `feat/agent-signing-key-provisioning`)

### What changed and why

`security.requirePeerVerification` verified earlier speakers' signatures and then handed the
receiving agent the transcript anyway: every failure was a log line. A signature also proved less
than it seemed. It covered only the content and the phase name, so a signed entry copied into
another discussion, into another phase or round of the same one, or retyped, still verified; the
verifier checked no nonce, so a duplicated entry verified twice; and key validity windows were
never enforced.

- **Fail-closed.** `GroupSigningGuard.viewFor` gives one verdict per transcript entry
  (`VERIFIED`, `NOT_AGENT_AUTHORED`, `RECEIVERS_OWN`, `UNSIGNED`, `UNKNOWN_KEY`, `REVOKED`,
  `OUT_OF_WINDOW`, `BAD_SIGNATURE`, `WRONG_ROUND`, `DUPLICATE`). For a member that requires peer
  verification, every entry another agent wrote that does not verify is replaced, in place, by a
  `[withheld: ... because ...]` placeholder before that member's phase prompt
  (`GroupContextBuilder.buildPhaseInput`) and its `groupTranscript` context (`MemberTurnExecutor`)
  are built. In place, so the raw indices window summaries are keyed on stay aligned. The
  placeholder names the speaker by its agent id and has no phase name, since the stored display
  name and phase name are as unverified as the content. Each withheld
  entry is logged at ERROR the first time per conversation, then at DEBUG.
- **The member's own entries** are not checked for it: they are in its own member conversation
  already, and a verifying member that does not sign (the validator allows it; a moderator is the
  usual case) was being asked to revise or defend a placeholder.
- **What the signature covers.** The payload is built in one place for signing and verification:
  the group conversation id, the round (stored on the entry as the new `signatureRound`), the phase
  index and name, the entry type, the content, the speaker's display name, the target agent, the
  error reason and the key version (the envelope's own `keyVersion` is outside its canonical form).
  A reply the engine records as a `DISSENT` or a `BID` is signed again as that type
  (`MemberTurnExecutor.sign`). Unpaired UTF-16 surrogates become U+FFFD before signing, and text
  holding one never verifies: Java's UTF-8 encoder writes one as `?`, so it signed the same bytes as
  the text with `?`, and a store writing real UTF-8 turned an honest entry into a failure.
- **Replay.** A nonce is accepted once per transcript; only an occurrence that verifies claims it,
  so a forgery carrying a genuine nonce cannot get the genuine entry withheld. Rounds only grow along
  the transcript, and the current round starts at `roundStartTranscriptIndex`, so an entry moved
  into another round is withheld (`WRONG_ROUND`). Moving one between repeats of a phase within a
  round is not detected. An entry signed before its conversation began (30 s skew) is withheld; that
  rule rests on the stored `created` time.
- **Revocation and windows.** A key revoked through the new admin endpoint (base branch) vouches for
  nothing, whatever time an entry claims (`REVOKED`); the signer does not sign with a revoked newest
  key. A `validFromMs`/`validUntilMs` window is enforced on both sides, but against the time the
  signer wrote, so it limits honest use and does not stop a leaked key.
- **Non-agent entries.** Which entries must verify is decided by the speaker id, while a prompt
  shows the display name; a store writer could put any text under an agent's name by giving it a
  non-agent id. A verifying member now sees such entries as `User`, `System` or
  `<name> (not an agent, unverified)`, and a group save refuses those names for an agent member.
  Group saves also refuse two members with the same display name (ignoring case), so two verified
  voices never share one name either.
- **Summary and ballots.** The stored window summary is free text no signature covers, so a
  verifying member gets the truncation marker instead. A ballot lists the options the tally counts,
  resolved from the stored transcript: a verifying voter from whom the synthesis was withheld got
  no options, could not cast a parseable ballot, and counted against quorum.
- **Unreadable receiver.** If a member's configuration cannot be read, the setting this node last
  read for it applies; if it never read one, the member's turn is recorded `SKIPPED` instead of
  running on a transcript filtered for a setting nobody knows. Before, every member, verifying or
  not, was treated as verifying.
- **Cost.** Each Ed25519 check is remembered (Caffeine, by SHA-256 of key, signature and signed
  bytes), so a verifying member no longer re-runs it over the whole transcript twice per turn; the
  revocation and window are re-checked each time. Agent configurations are read per version through
  an `AgentConfigurationCache`. The per-conversation log de-duplication is a bounded cache, so a
  conversation that pauses on one node and ends on another no longer leaks its entry.
- **Signer.** `signOutgoingMessage(...)` became `sign(gc, entry)`. It no longer registers its own
  nonce with `NonceCacheService`: a freshly minted UUID can never collide, and with
  `eddi.cluster.degraded.nonces=reject` a NATS outage silently turned signing off. Nothing calls
  that service any more, so its two `eddi.a2a.signing.nonce.*` properties do nothing
  ([configuration reference](../configuration-reference.md) and
  [clustering](../clustering.md) say so); deleting the class is a follow-up. A signing agent that
  cannot sign now logs at ERROR, since peers that verify will withhold the entry.
- **Docs, Manager, operator.** [`architecture.md`](../architecture.md#cryptographic-agent-identity)
  (signing, replay, revocation, peer verification; the audit-signing sentence now points to the
  audit ledger; "non-repudiation" dropped, since the deployment holds every key; the RFC 8785
  claim corrected) and a [Signed contributions](../group-conversations.md#signed-contributions)
  section. The Manager's "Require peer verification" description no longer says failures are only
  logged (all 11 locales). The Platform Operator's prompt says what a verifying agent sees and who
  must sign (operator revision bumped).

Nothing had been signed in the wild (provisioning only lands with the base branch), so the change
of the signed bytes needs no migration.

### Upgrading

A group whose members share a display name, or with an agent member named `User`, `System` or
`... (not an agent, unverified)`, is refused on its next save until the names change;
stored groups still load and run. A verifying member in a windowed discussion no longer gets the
rolling summary.

### Not filtered yet

The prompts the engine writes itself for role turns (convergence judge, facilitator briefing, vote
tie-break, dissent round, task-force plan, execute, verify and bid) quote the round just run, task
records or a short excerpt directly. Agent follow-up answers, facilitator interventions and the
reformatted task-force verification entry are recorded unsigned, so a verifying member sees them
withheld.

**Files:** [`GroupSigningGuard.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupSigningGuard.java),
[`GroupContextBuilder.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupContextBuilder.java),
[`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`PhaseExecutionEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/PhaseExecutionEngine.java),
[`TaskForceEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`GroupConversation.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java),
[`AgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`SignedEnvelope.java`](../../src/main/java/ai/labs/eddi/configs/agents/crypto/SignedEnvelope.java),
[`JacksonCanonicalizer.java`](../../src/main/java/ai/labs/eddi/configs/agents/crypto/JacksonCanonicalizer.java);
tests `GroupSigningRoundTripTest` (real Ed25519), `GroupSigningGuardTest`, `MemberTurnExecutorTest`,
`PhaseExecutionEngineTest`, `TaskForceEngineTest`,
`AgentGroupStoreTest`, `GroupConversationTest`.
