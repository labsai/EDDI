## 🔒 fix(apicalls): auto-vaulted properties carry a provenance marker; the guard requires it (2026-09-20)

**Repo:** EDDI (`fix/vault-references-in-templates`)

### Why

`ConfigReferenceGuard.autoVaultReferences` decided whether a conversation property named by an
HTTP-call template may resolve a vault secret by **looking at the value**: it accepted the property
when its value was character-for-character what `PropertySetterTask.autoVaultSecret` would have
written for that property, under this conversation's agent and tenant. Two PR reviewers (Copilot,
CodeRabbit) asked for a provenance check instead, and the previous entry recorded why it was not
done then: `Property` carried no marker. A `scope: "secret"` instruction stores its vault reference
with `scope: conversation`, indistinguishable on disk from a property a template wrote from user
input, a model reply or an API response — so `${vault:<agentId>.apiKey}` was a string an attacker
could simply produce, and the shape test accepted it.

What that bought an attacker was bounded (the key is derived from the agent and the property name
the template reads, and the request goes to the endpoint the configuration names), which is why it
shipped. It is still a value the configuration never wrote being resolved into an outgoing request.

### What changed

- **`Property.autoVaulted`** (new, `Boolean`) — the provenance marker, set by
  `PropertySetterTask.autoVaultSecret` and by **nothing else**. No property-instruction field maps to
  it (`convertPropertyInstructions` reads a fixed key set), and no REST endpoint takes a `Property` as
  a request body, so "marked" means "this process vaulted it" rather than "this value looks vaulted".
- **`ConfigReferenceGuard`** requires the marker before it will allow a reference read through
  `{properties.x}`. The agent/property/tenant comparison is kept behind it — redundant by
  construction, since `autoVaultSecret` derives all three itself, and kept as the bound that still
  holds if a marked `Property` ever reaches memory from somewhere other than that method.
- **`ApiCallExecutor`** passes the live `Map<String, Property>` from `IConversationMemory` into
  `buildRequest` → `resolveGuardedVariables` → the guard. `ConversationProperties.toMap()` — what
  templates and, until now, the guard see — flattens each `Property` to its raw value and loses the
  marker, so it needs a channel of its own. Read from memory at build time, not captured earlier: a
  pre-request property instruction writes through to the same map between `execute` being called and
  the request being built. Threaded through `execute`, `resolve` and `executeFireAndForgetCalls`.
- **`MemoryCheckpoint.copyProperties`** carries the marker across the deep copy. It clones through the
  all-args constructor, which does not take the new field — a rollback that dropped it would turn
  every later API call using that secret into a refusal.
- `docs/secrets-vault.md`: the auto-vaulted-property case now rests on provenance, and what an
  unmarked property means.

### Decision: unmarked is refused, not grandfathered

`null` covers two cases that cannot be told apart — a property written from conversation data, and
one written into a conversation document before the field existed. Accepting the pair for
compatibility would leave the hole open permanently, because the attacker's property is unmarked
too; the fix would be decorative. So unmarked fails closed.

The cost is a conversation that auto-vaulted a secret under an earlier release and makes the API
call after the upgrade: the call is refused with the error that names the field and the reference,
and re-running the `scope: "secret"` instruction (the user supplies the secret again, or a new
conversation starts) marks it. Bounded — it needs the vault enabled, which is not the shipped
default — and recoverable. A permanent fail-open is neither.

Deserialization stays backward compatible in the mechanical sense: the field is absent from every
document already in MongoDB and reads back as `null` rather than failing, and EDDI's global
`NON_NULL` inclusion means an unmarked property does not gain the field on write either.

### Verification

- `ConfigReferenceGuardTest` 10 → 13: the three existing auto-vault cases kept (`autoVaultProperty`,
  `autoVaultTenantIsPinned`, `autoVaultOwnTenant`, now stating the marker explicitly — tenant pinning
  still refuses a *marked* property under a foreign tenant, so provenance is necessary and not
  sufficient), plus an unmarked property holding the exact reference, an explicit `FALSE`, and no
  properties at all.
- `ApiCallExecutorConfigReferenceTest` +1: the request `autoVaultedPropertyHeader` sends, refused
  byte-for-byte when the same property is unmarked — the vault is never asked and nothing is sent.
- `PropertySetterTaskSecretScrubTest` +2: a `scope: "secret"` write is marked; an ordinary write of
  the identical string is not.
- `PropertyTest` +5 (JSON round-trip, an unmarked property omits the field, a pre-marker document
  reads back unmarked), `MemoryCheckpointTest` +1 (the marker survives the deep copy).
- Mutation-checked one at a time: the guard ignoring the marker fails 3 tests, `autoVaultSecret` not
  writing it fails 1, the checkpoint clone dropping it fails 1.
- apicalls, properties, memory and secrets suites plus the repo-wide guards: 6479 tests green.

## 🔒 fix(apicalls): configuration references work in templated HTTP-call fields; data-supplied ones are refused (2026-09-17)

**Repo:** EDDI (`fix/vault-references-in-templates`)

### Why

HTTP-call values are rendered by Qute before `${vars:…}`, `${vault:…}`, `${eddivault:…}`, `${caller:…}` and
`${connection:…}` are resolved. Only `caller` had a pass-through namespace resolver, so every other reference
in a URL, header, body or query parameter failed the call with "No namespace resolver found" — including
`${connection:name}` headers, the documented way to use connections, and the vault references
`docs/secrets-vault.md` lists as supported. Vault was kept failing on purpose, because a resolved body was
stored unredacted.

The resolvers run on the *rendered* string, so a reference that conversation data put there was resolved
too: a template substituting user input, a model reply or an API response sent the plaintext of any vault
secret named in that data (grants are checked at deploy, not at read). That was independent of the
namespace failure.

### What changed

- `ReferencePassThroughNamespaceResolver` (new base; `CallerNamespaceResolver` now extends it) and
  `ConfigReferenceNamespaceResolvers` with pass-through beans for `vault`, `eddivault`, `connection` and `vars`.
- `ConfigReferenceGuard` (new): after rendering and before resolution, every credential reference
  (`vault`, `eddivault`, `connection`, `caller`) must appear in that field's configuration template, or be the
  value of a property the template names that is exactly this agent's auto-vault reference
  (`${vault:<agentId>.<name>}`, under this conversation's own tenant). Otherwise the call is refused, naming
  the field. `${vars:…}` is not itself a credential reference, but a variable may hold one — see the second
  review fix below for how that is guarded.
- `ApiCallExecutor` records the vault plaintexts it substitutes (`BuiltRequest.resolvedSecrets`) and redacts
  them by value from the memory request record (`RequestRedactor.redactResolvedSecrets`), the approval
  preview (`ResolvedRequest.withoutResolvedSecrets`, fingerprint unchanged) and the request log lines.
- `docs/secrets-vault.md`: where references are resolved, and the rule above.

### Verification

- `ConfigReferenceNamespaceResolversTest`, `ConfigReferenceGuardTest` (10) and
  `ApiCallExecutorConfigReferenceTest` (15, incl. the nested `VariableIndirection` group) — the executor with
  the real Qute engine, not a templating stub — plus `RequestRedactorTest`'s new `SafeRequestLog` group (5),
  `ApiCallExecutorTest`, `ApiCallExecutorSecretContextTest`, `ApiCallExecutorBatchPrincipalPropagationTest`,
  `ResolvedRequestTest`, `CallerNamespaceResolverTest`. The apicalls, templating, secrets, connections,
  variables and properties suites are green (6353 tests), as are the repo-wide guards.
- Mutation-checked, one fix at a time: redacting after formatting instead of before, dropping the
  post-variable-expansion guard pass, accepting any tenant prefix on an auto-vault property, removing the
  fail-closed throw, and resolving a second time to build the value each fail a test.
- End to end on the packaged build (MongoDB, vault on, recording mock API): `${vars:}` in the target URL and
  `${vault:}` in a header and the body reach the API; an injected reference to another agent's secret is
  refused and never sent; neither secret appears in the response, the conversation read, the stored
  conversation or the server log (17/17). Run on the pre-merge branch; **not** re-run after the merge and the
  review fixes below, which add a fail-closed path a packaged run would exercise differently.

### Merged `main` (2026-09-20)

`main` had moved 28 commits on. Two conflicts:

- **`ApiCallExecutor.executeFireAndForgetCalls`** — `main` had moved batch request *building* onto the turn's
  own thread (each iteration getting its own copy of the template data) so an unsatisfiable reference fails
  the turn instead of a worker nobody reads; this branch had changed the same loop to carry `BuiltRequest`
  so the log line could be redacted. Resolved by keeping `main`'s structure and collecting `BuiltRequest`
  rather than `IRequest`. `main`'s `rejectExpiredSecretContext` calls in `buildRequest` auto-merged beside
  the guard calls and were kept on all four fields.
- **`docs/changelog.md`** — both sides added an entry at the top; both kept.

### Review fixes

Five findings from the PR review, all in the security path:

- **The log line is redacted before it is formatted.** `RequestWrapper.toString()` folds newlines and cuts the
  body at 150 characters, so redacting its *output* by exact value missed a secret carrying a newline (a PEM
  key) or straddling the cut. New `RequestRedactor.safeRequestLog(IRequest, Set)` reads the raw components
  from `IRequest.toMap()`, redacts those, and shortens afterwards; all three call sites use it.
- **`${vars:…}` can no longer smuggle a credential reference in.** A global variable is allowed to hold
  `${vault:…}` or `${connection:…}`, so a data-supplied `${vars:x}` passed the guard (not yet a credential
  reference) and became one after expansion. `ApiCallExecutor.resolveGuardedVariables` now guards again after
  expansion, against the configured template expanded the same way — a configured variable's reference is
  allowed, a data-supplied one is refused. Covered for URL, body, header and query parameter; in the path the
  reference never forms at all, because `pathSafeView` percent-encodes what data puts there (now asserted).
- **The auto-vault property exception no longer crosses tenants.** It accepted any `<tenant>/` prefix, so a
  user-supplied `${vault:victim/thisAgent.apiKey}` read another tenant's secret of that key name. The
  reference is now compared character-for-character against what `autoVaultSecret` would have written for that
  property under *this* conversation's tenant. Also removes the per-call `Pattern.compile`.
- **An unresolvable reference fails closed.** `SecretResolver.resolveValue` leaves a reference it cannot
  resolve in place, and the call went out carrying the literal `${vault:name}` as its credential. It now
  refuses, naming the field and the reference — the rule `SecretResolver.requireResolved` already applies to
  LLM client parameters.
- **One resolution per reference.** The bookkeeping pass and the substitution pass resolved separately, so a
  rotation between them put the new plaintext in the request while only the old one was in the redaction set —
  the value actually sent was the one that survived into memory, previews and logs. `resolveSecrets` now
  builds the string from the same resolutions it records.

Not fixed here, and why: the auto-vault exception was still a *shape* test rather than a provenance test.
`Property` carried no "auto-vaulted" marker — a `scope: "secret"` instruction stores its vault reference with
`scope: conversation`, exactly like any other property — so a real provenance check needed a marker on the
persisted property model. What was left was bounded: the reference is derived from this agent and the
property name the template reads, so data could not choose which secret is read, and it goes only to the
endpoint the configuration names. **Closed on this branch by the 2026-09-20 entry above**, which adds that
marker and makes the guard require it.
