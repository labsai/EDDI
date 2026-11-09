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
  `OUT_OF_WINDOW`, `BAD_SIGNATURE`, `WRONG_ROUND`, `DUPLICATE`, `ENGINE_SYNTHESIS`, `NO_SUCH_AGENT`). For a member
  that requires peer verification, every agent entry that must verify and does not is replaced, in place, by a
  `[withheld: ... because ...]` placeholder before that member's phase prompt
  (`GroupContextBuilder.buildPhaseInput`) and its `groupTranscript` context (`MemberTurnExecutor`)
  are built. In place, so the raw indices window summaries are keyed on stay aligned. The
  placeholder names the speaker by its agent id and has no phase name, since the stored display
  name and phase name are as unverified as the content. Each withheld
  entry is logged at ERROR the first time per conversation, then at DEBUG. A record with no
  content (a skip, an error, a pass; verdicts `STATUS_RECORD` and `NON_AGENT_STATUS_RECORD`) is
  unsigned by design, so it keeps its place, speaker id, type, phase index and target but loses its
  error reason, display name and phase name, which a store writer could otherwise fill under a
  member agent's id for a verifying member's `groupTranscript` to carry.
- **The member's own entries** are checked like a peer's when it signs: every honest one is signed,
  and REVISION and DEFENSE quote its latest entry from the group transcript as the answer it revises
  or defends, so an answer planted under its id, or its own signed answer edited in the store, is
  withheld instead. A verifying member that does not sign (the validator allows it; a moderator is
  the usual case) has nothing to check them against and is shown them as stored, `RECEIVERS_OWN`;
  without that it was being asked to revise or defend a placeholder.
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
  signer wrote, so it limits honest use and does not stop a leaked key. Revocation applies to every
  prompt built after it; what a member was already shown stays in its own conversation history,
  which its model sees again until the history window drops it (documented; a new discussion
  clears it).
- **Deleted agents.** Whether a speaker is an agent was decided by whether the agent store had a
  current version of it. A soft delete (MongoDB throws, PostgreSQL returns null) or a permanent one
  therefore turned every entry of that agent into a non-agent's: shown relabelled instead of
  withheld. So revoking a compromised agent's key and then deleting it, the ordinary response,
  put its revoked entries back in front of verifying members. Now an entry under an id with no
  current agent is withheld (`NO_SUCH_AGENT`) when it carries a signature, which only agents give,
  or when an agent turn of the discussion ran under that id (its member conversation is recorded
  under it); a human member's or a nested group's entry still passes labelled. The placeholder
  names the id followed by `(not an agent, unverified)`: the id is whatever the entry was stored
  under, which no agent store vouches for, so a planted one cannot put unlabelled text in the
  speaker's place. A deleted agent's
  genuine signed entries are withheld too: the trust anchor is the speaker's current
  configuration, and a deleted agent has none.
- **Non-agent entries.** Which entries must verify is decided by the speaker id, while a prompt
  shows the display name; a store writer could put any text under an agent's name by giving it a
  non-agent id. A verifying member now sees such entries as `User`, `System` or
  `<name> (not an agent, unverified)`, and a group save refuses those names for an agent member.
  Under `ANONYMOUS` scope (Delphi's later rounds), where every speaker is `Anonymous`, such an entry
  is `Anonymous (not an agent, unverified)` in the prompt and in `groupTranscript`; before, the
  scope dropped the label and a store writer's `system` opinion read like a verified peer's.
  Group saves also refuse two members with the same display name (ignoring case), and an agent
  member named `Moderator` while the moderator is not a member or `Facilitator` while an enabled
  facilitator is another agent, the names the engine signs those roles' entries under; so two
  verified voices never share one name either.
- **Synthesis under the engine's name.** A synthesis is looked up by its type and quoted without its
  speaker (the dissent prompt, the vote's options), so the relabelling did not reach it. The engine
  never records one under `system`, `user` or no speaker, and a verifying member now has such an
  entry withheld (`ENGINE_SYNTHESIS`). A reviewer's edit and a human or nested-group moderator's
  synthesis are unsigned by nature and still shown.
- **Summary and ballots.** The stored window summary is free text no signature covers, so a
  verifying member gets the truncation marker instead. A ballot lists the options the tally counts,
  resolved from the stored transcript: a verifying voter from whom the synthesis was withheld got
  no options, could not cast a parseable ballot, and counted against quorum. (Superseded by the
  entry "every prompt a verifying member receives is built from its verified view": a ballot now
  lists the options as the voter sees them, and a vote on a synthesis that does not verify does
  not run.)
- **Unreadable receiver.** If a member's configuration cannot be read, the setting this node last
  read for it applies; if it never read one, the member's turn is recorded `SKIPPED` instead of
  running on a transcript filtered for a setting nobody knows. Before, every member, verifying or
  not, was treated as verifying. The check runs before the member conversation is started, since
  the first turn with a conversation is the one that is given the discussion's attachments.
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
  (signing, replay, revocation, peer verification; "non-repudiation" dropped, since the deployment holds every key; the RFC 8785
  claim corrected) and a [Signed contributions](../group-conversations.md#signed-contributions)
  section. The Manager's "Require peer verification" description no longer says failures are only
  logged (all 11 locales). The revoke dialog says again that peers stop trusting every message
  signed with the revoked key: the base branch's copy says peer verification does not refuse a
  revoked versioned key yet, which this branch makes untrue; its best-effort vault delete and
  legacy-v0 wording stay (all 11 locales). The Platform Operator's prompt says what a verifying agent sees and who
  must sign (operator revision bumped).

Nothing had been signed in the wild (provisioning only lands with the base branch), so the change
of the signed bytes needs no migration.

### Upgrading

A group whose members share a display name, or with an agent member named `User`, `System` or
`... (not an agent, unverified)`, or `Moderator` / `Facilitator` as above, is refused on its next
save until the names change; stored groups still load and run. A verifying member in a windowed
discussion no longer gets the rolling summary. A member that both signs and verifies has its own
unsigned entries (a signing failure, or answers from before signing was switched on) withheld from
itself, as its verifying peers already do. A continued discussion withholds from verifying members
every entry of a member agent that has since been deleted.

### Not filtered yet

The prompts the engine writes itself for role turns (convergence judge, facilitator briefing, vote
tie-break, dissent round, task-force plan, execute, verify and bid) quote the round just run, task
records or a short excerpt directly. Agent follow-up answers, facilitator interventions and the
reformatted task-force verification entry are recorded unsigned, so a verifying member sees them
withheld. The entry "every prompt a verifying member receives is built from its verified view"
filters those prompts and signs those entries for their agent.

The [group-conversations](../group-conversations.md#signed-contributions) page now names
these limits too: an entry's stored `timestamp` is not covered by its signature, an earlier round's
signed entry can be moved past a later round's question without being detected, and a group saved
before display names had to be unique keeps loading with its duplicate names until it is saved
again. The operator prompt merged from the key-provisioning branch said peer verification ignores
revocation; on this branch it does not, and the prompt says so.

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
