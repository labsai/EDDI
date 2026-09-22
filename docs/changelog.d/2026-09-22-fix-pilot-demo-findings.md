## 🐛 fix(llm): `includeFirstAgentMessage` dropped the user's own first turn (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

The flag exists to strip EDDI's opening greeting so the history starts on a user turn —
`docs/langchain.md` advised setting it `false` for Anthropic, which used to reject an
assistant-first conversation. It was implemented as *remove the first message*, full stop.

For the agent shape it was written for — one with an `ai.labs.output` step producing a
greeting at `CONVERSATION_START` — those are the same thing, and it has worked for years.
An agent built with **no** output step (a group member that only answers) opens on the
**user's** turn. That turn was deleted, the history went out empty, and Anthropic answered:

```
invalid_request_error: messages: Field required
```

A flag whose only purpose is to satisfy Anthropic's first-message rule was, in that
configuration, what made Anthropic reject the request. Two things hid it: Ollama accepts an
empty message list, so a local smoke test passes on a config that cannot work against the
real provider; and the documented advice reads as universal while only covering the
standard agent shape.

The premise has also expired. The Anthropic Messages API reference no longer documents a
"first message must be the user's" rule, and an assistant-first history is accepted.

### What changed

- [`ConversationLogGenerator.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationLogGenerator.java)
  drops the first message only when its role is `assistant`. Behaviour is unchanged for
  every agent that has a greeting — for those the first message genuinely is the agent's —
  so the fix is strictly additive.
- [`ConversationHistoryBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java)
  carried a second copy of the same unconditional `removeFirst()` in
  `generateMessagesFromOutputs`. Both callers pass `skipSteps > 0` today, so that branch is
  currently unreachable and has no behavioural test of its own; it is written correctly
  rather than left as a trap for the next caller, and the comment says so.
- [`docs/langchain.md`](../langchain.md): the blanket "set it `false` for Anthropic" is gone
  from the parameter table, the Anthropic example and the troubleshooting section, replaced
  by an entry for the `messages: Field required` failure itself.

**Tests:** four cases in `ConversationLogGeneratorTest` and four in
`ConversationHistoryBuilderTest` (including the token-aware path). The pre-existing
`excludeFirst` case asserted the bug — it built a **user**-first history and asserted the
message was removed — and was rewritten. Mutation-checked: restoring the unconditional
`removeFirst()` fails five of them.

### And now deprecated

The flag's only documented reason to exist has expired, so it is marked **deprecated**:
`LlmTask` logs a WARN naming the task the first time each configured task uses it, the
parameter table says not to use it in new configs, and `docs/langchain.md` gains a
*Deprecated parameters* section explaining what to do instead.

**Deprecated rather than removed**, deliberately. Agent behaviour lives in JSON stored in
MongoDB and imported from ZIPs — per `AGENTS.md`, the one backward-compatibility boundary
this codebase has. Silently ignoring a parameter an author set on purpose would be *worse*
than honouring it: an agent that genuinely wants its greeting withheld would start sending
it, with no diagnostic. The flag keeps working exactly as before.

Once per task, not once per turn: an LLM task runs on every message of every conversation,
and a per-turn WARN is a flood operators learn to filter out — which is the same as not
warning at all.

---

## 🔒 fix(security): a missing `role-claim-path` 403'd every admin, silently (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

`quarkus.oidc.roles.role-claim-path=realm_access/roles` is one line in
`application.properties`, and the released 6.4.0 image shipped without it. quarkus-oidc then
falls back to its default `groups` claim — which is also `eddi.workspaces.groups-claim`'s
default — so any account belonging to a Keycloak group had its EDDI roles **replaced** by its
group paths, and every `@RolesAllowed` endpoint answered **403 with an empty body and not one
log line**. The shipped realm puts the seeded `eddi` administrator in `/engineering`;
accounts in no group fell through to `realm_access` and worked. It presents as "this one
account is broken", not as a configuration gap, which is what made it cost hours to find.

### What changed

- [`AuthStartupGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/AuthStartupGuard.java)
  gains `rolesClaimDiagnostic()`, logged at ERROR on startup whenever OIDC is enabled and the
  roles claim path is unset, blank, or equal to the workspaces groups claim. It names
  `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH` and the 403 symptom. Pure and package-private, so the
  branch is assertable without a container. It runs before the launch-mode branch: the auth
  E2E tier runs in `TEST` and the diagnostic matters there too.
- [`OidcRolesClaimConfigTest.java`](../../src/test/java/ai/labs/eddi/configs/OidcRolesClaimConfigTest.java)
  pins the property in the source tree — present, non-blank, `realm_access/roles`, and not
  equal to `eddi.workspaces.groups-claim`. Value-pinned rather than presence-only: an edit to
  `groups` would reintroduce exactly the failure and still pass a presence check.

The two halves are deliberate: the test stops it being dropped from the source, so the
runtime ERROR stays a warning about an operator override rather than about us.

---

## 🐛 fix(ui): `/manage/` answered 200 with an empty body (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

`GET /manage` served the app; `GET /manage/` served `content-length: 0`. The trailing slash
reaches `{path:.*}` as an **empty** path, `normalizeSlashPath` drops empty segments, and the
lookup became `getResourceAsStream("META-INF/resources/")` — a directory entry, which a
classloader answers with an open, empty stream rather than `null`. The missing-asset check
was satisfied, the `manage.html` fallback never ran, and the browser got nothing.

### What changed

[`RestManagerResource.java`](../../src/main/java/ai/labs/eddi/ui/RestManagerResource.java):
a path that normalizes to nothing resolves straight to the SPA shell, and the two fallback
sites are one `serveManagerIndex()` helper.

**Tests:** `RestManagerResourceTest` asserts the **lookup sequence** rather than the body —
the unit run resolves off an exploded `target/classes`, where directory names behave
differently from jar entries, so what must hold on every layout is that the resource base is
never asked for at all. Covers `""`, `"/"`, `"//"`, `"./"` and `"/./"`. Mutation-checked.

---

## ✨ feat(groups): a rejected decision is `REJECTED`, not `FAILED` (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

Rejecting a group discussion's recommendation at a HITL gate set the conversation to
`FAILED`, and the Manager rendered a red "Failed" badge on it. Semantically defensible — the
run did not complete — but it reads as a system error rather than as a recorded human
decision, and in a product whose selling point is the human in the loop that conflation is
the wrong way round: the run did exactly what it was asked.

### What changed

`GroupConversationState.REJECTED`, set by
[`GroupHitlCoordinator`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java)
on a `REJECTED` verdict. It permits **exactly** what `FAILED` permitted — the label is the
only thing that changed:

- terminal in `GroupLifecycleOps.isTerminalState` and in the coordinator's
  `persistedTerminalOverride` and cancel guards;
- closeable — `closeGroupConversation`'s CAS chain and its refusal message now come from one
  `CLOSEABLE_STATES` list, so a new terminal state cannot be added to the chain and left out
  of the error, which is what three hand-written `if` blocks beside a hard-coded sentence
  invited;
- `availableActions` answers `["close"]`, as for `FAILED`;
- ephemeral agents are reclaimed immediately, as for `FAILED`.

Documents written before this carry `FAILED` for a rejection and are **left alone**: nothing
in them distinguishes the two, so a migration could only guess.

**Rolling downgrade is one-way.** Jackson serializes the enum by name, so a document written
by this version and read by an older EDDI fails `valueOf`. Upgrading a cluster is safe;
rolling back a node that has already served a rejection is not, until those documents age
out. The same goes for a Manager older than its backend: `GroupConversationState` is a
closed union there too.

The cadence reconciler in `TeamCadenceService` was the one place the new state had to be
routed by hand — it switches on the enum with a `default` arm rather than exhaustively, so
`REJECTED` fell through to "still running" and wedged the standing team's claim for
`eddi.groups.cadence.claim-ttl` (default 24 h). Caught in review; it is the only such switch
in `src/main`, and it now carries a comment saying so.

### Also

[`RestGroupConversation.setDecidedByFromIdentity`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java)
writes a blank principal name as `null` rather than `""`. The server has always overwritten
the client's claimed `decidedBy` from the authenticated principal, which is correct for an
audit ledger — but with `eddi.security.allow-unauthenticated=true` there is no principal, and
the ledger recorded `"decidedBy": ""`. The audit writer already renders a null decider as
`"unknown"`, so the unauthenticated case now lands on that same honest value.

---

## ✨ feat(groups): say at save time when debate roles turn a synthesis into a verdict (2026-09-22)

**Repo:** EDDI (`fix/pilot-demo-findings`)

### Why

Giving members structural roles switches the SYNTHESIS phase onto the debate-judgment
prompt: the moderator is asked for `{"winner": …, "scores": …}` and its own synthesis
instruction is not used. A grant board whose members carried `role: PRO` and `role: CON` had
its chair return a scoring verdict instead of the recommendation its system prompt specified.
Correct for a debate-scoring exercise, wrong for anything else — and discoverable only by
running it, because nothing in the configuration says so.

### What changed

[`AgentGroupStore.debateVerdictSynthesisPhaseNames`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java)
reports the phases that will take the verdict path, logged at **INFO** on create and update.
INFO rather than WARN, deliberately: for a real debate this is the intended behaviour and the
note is its documentation, not a complaint.

It mirrors `GroupContextBuilder.isDebateJudgment` with the two substitutions a config-time
check has to make — an `ARGUE`/`REBUTTAL` *phase* before the synthesis in place of argument
entries on the transcript, and only `participants: "MODERATOR"` phases, which are the only
ones whose speaker is resolvable from configuration. A synthesis open to other participants
is left unreported rather than guessed at. Preset-expanded, or it would be inert for exactly
the style it matters most for. `moderatorlessPhaseNames` now shares the same `resolvedPhases`
helper so the two cannot drift.

**Tests:** eleven cases in `AgentGroupStoreTest`, one per condition — two sides, a chair that
is itself a debater, a moderator-less roster, an explicit `inputTemplate` (the documented
opt-out), arguments after the synthesis, and a ROUND_TABLE with debate roles.

```decision-log
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
```

```regression-note
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
```
