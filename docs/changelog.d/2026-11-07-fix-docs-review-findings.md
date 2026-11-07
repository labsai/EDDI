## 🐛 fix: findings from the documentation and comment reviews — dead config fields, `lookupIfKnown`, vault messages, counterweight metrics, classifier tool-call layout (2026-11-07)

**Repo:** EDDI (`fix/docs-review-findings`)

### What changed and why

A documentation review turned up five places where the code did not do what its config surface,
Javadoc or comments said. Each is fixed test-first.

**1. `dream.batchSize` / `dream.maxUsersPerRun` — removed from the model, still accepted on input.**
Nothing ever read them. They describe a sweep over all users in batches, which was never built: a
dream fire consolidates exactly the one user its schedule names, so there is no batch to size and
no per-run user count to cap. Wiring them up would mean inventing that sweep. They are no longer
fields of `DreamConfig`; `@JsonIgnoreProperties({"batchSize", "maxUsersPerRun"})` keeps a stored
agent or an imported ZIP that still carries them deserializing, including through the strict mapper
that REST writes use, and the next save drops them. The Manager's agent editor offered a "Batch
Size" input that wrote to the dead field; it is gone, along with the two fields in its
`DreamConfig` type. The i18n key stays in the locale files (the i18n check flags orphans and
missing keys, not unused ones).

**2. `ScheduleConfiguration.tenantId` / `allowSelfScheduling` — deprecated, kept.** Neither has
ever been read by a fire, and neither the Javadoc nor `scheduling.md` promised a behaviour — they
only described the PUT carry-over. `tenantId` has no tenant model to plug into on the fire path,
and self-booking is already governed by the LLM task whitelisting `schedule_followup`, so a second
switch on an existing schedule would only be a second place to look. Unlike the Dream fields these
are real columns (PostgreSQL) and indexed fields (MongoDB) with explicit PUT carry-over semantics,
so removing them means schema churn for no gain. They are marked `@Deprecated` on the field with
Javadoc on the accessors saying they are stored and not read; the field table in `scheduling.md`
now lists both as deprecated and not read.

**3. `lookupIfKnown` threw `ClassCastException` on a JSON boolean.**
`IParserExtensionProvider.extractLookupIfKnownParam` cast the value to `String`, so
`"lookupIfKnown": true` failed the parser workflow. It now accepts a `Boolean` as is and reads
anything else by its trimmed string form (`"true"`, case-insensitive). Both providers that read it,
Damerau-Levenshtein and phonetic, now advertise it in their `ExtensionDescriptor`
(`BOOLEAN`, optional, default `false`); the phonetic provider had no descriptor entries at all.
`semantic-parser.md` documents the key.

**4. Helm `values.yaml` named the wrong chart version.** It said `nats.buildProfileImage` stopped
being read "from chart < 2.5"; the release that made it so (775edae28) moved the chart from 2.6.0 to
2.7.0, which is what `NOTES.txt` says. Fixed to 2.7.0, and a test now asserts the two copies agree.
Chart `version` 2.14.0 → 2.14.1 (comment-only change, so a patch) with `EXPECTED_CHART_VERSION`.

**5. `GET /secretstore/secrets/health` reported the ArC client proxy.** `RestSecretStore` took
`getClass().getSimpleName()` of the injected `@ApplicationScoped` `ISecretProvider`, which under
Quarkus is `VaultSecretProvider_ClientProxy`, not the documented `VaultSecretProvider` — the
released 6.7.0 image answers exactly that. It now
unwraps the proxy with `ClientProxy.unwrap` before naming the class.

### From the Java comment review

**6. `LifecycleInterruptedException(message, cause)` dropped the cause.** It called `super(message)`;
now `super(message, e)`. `RetryConfiguration`'s interrupted back-off is the caller, so the interrupt
now stays on the stack trace.

**7. `ClusterConfig.redactUserInfo` — `\s` kept, made explicit, guarded.** In a Java string `"\s"`
is the space escape, so the class `[^/@\s]` excluded only spaces. The suggested fix, the regex
`\\s`, would have been a regression: a tab or newline *inside* the userinfo would then end the
match before the `@`, and the credentials would be logged in clear. The class is now spelled with a
literal space (same behaviour, no ambiguity), and a test pins that a tab or newline in the userinfo
is still redacted.

**8. `ContractedWordNormalizer` mapped "won't" to "would not".** Now "will not"; the existing test
asserted the wrong expansion and is corrected. The other entries are right (the `'s` forms are
ambiguous between "is" and "has"; "is" is kept).

**9. `RemoteApiResourceSource.resolveLatestAgentVersion` threw a bare `RuntimeException`** for an
agent the remote does not list. It is now a `RemoteReadException` like every other source-read
failure. The only caller (`readAgent`) already wrapped it in one, so the HTTP status (502) was
right; the cause type and message were the inconsistency.

**10. The vault's DEK-failure recovery hint named `POST …/reset` without `?confirm=true`,** which
that endpoint refuses with 400. All three hints (two exception messages and the master-key adoption
warning) now carry it.

**11. `AgentStoreClientLibrary` logged under `AgentFactory`'s category.** Now its own.

**12. `CounterweightService` counted an unknown `level` as `normal`.** An unknown level resolves no
preset, and the early "no injection" return ran before the switch whose `default` arm warns and
counts `unknown`, so that arm was unreachable and a typo such as `"stirct"` looked like a healthy
normal activation. The level is classified first: unknown warns and counts `unknown` (only), and
`normal` with custom instructions — which also fell into the `default` arm — counts `normal`.

**13. `ClassifierGuardrail` without a `promptTemplate` sent tool-call arguments as the user's
message.** `buildMessages` chose the conversation layout on `phase == OUTPUT`, while
`GuardrailModelClient.templateData` uses `phase != INPUT`. With a template, tool-call arguments fill
`{output}` (as `tool-call-guardrails.md` documents); without one they went out as a plain user
message, so the classifier judged the model's own tool call as if the user had typed it. Both now
treat every non-input phase as model output: the user's last message, then the arguments as the
assistant's turn.

**14. The vault-disabled banner and the compliance check misstated audit signing.** The banner said
audit entries "will NOT have HMAC integrity signatures", but `eddi.audit.hmac-key` signs the ledger
without a master key. It now says entries are signed only if `EDDI_AUDIT_HMAC_KEY` is set. The
compliance check's logic was already right (it warns only when both keys are missing); its banner
and its startup failure now say "neither … nor" and list both ways to enable signing.

**Checked and left alone:**

- `PostgresHealthCheck` does not mark readiness DOWN on the MongoDB default. Probed against the
  released `labsai/eddi:6.7.0` image: on MongoDB and on PostgreSQL alike, `/q/health/ready` lists
  no "PostgreSQL connection" check at all — the `@DefaultBean` is never selected, since other
  `HealthCheck` beans exist. PostgreSQL readiness is covered by Agroal's own "Database connections
  health check", which is present and UP on both. So the suspected failure does not happen; the
  class is dead code, left for a follow-up rather than changed here.
- `ScheduleFireExecutor.creatorUseCheck` and lesson-consolidation schedules: such a schedule cannot
  carry both an `agentId` and a creator snapshot. `RestGroupLessons` sets neither, REST create and
  ZIP import refuse a body with the lesson marker (`rejectLessonConsolidationBody`), and a PUT
  carries over the stored null `creatorTeams`, which skips the check. Not reachable.

### Tests

New: `LookupIfKnownConfigTest`; `RestSecretStoreTest` (proxied and unproxied provider name);
`AgentConfigurationTest` (legacy Dream fields deserialize strictly and are not written back);
`DeploymentManifestsTest#buildProfileImageRemovalVersionAgrees`; `LifecycleInterruptedExceptionTest`;
`JetStreamDeadLetterStoreTest` (tab/newline userinfo); `RemoteApiResourceSourceMissedBranchTest`
(unlisted agent); `AgentStoreClientLibraryLoggerTest`; `CounterweightServiceTest` (unknown level,
normal with custom instructions); `ClassifierGuardrailTest` (tool-call phase, no template);
`VaultStartupBannerTest`; assertions added to `ComplianceStartupChecksTest`, `VaultKeySafetyTest`,
`VaultSecretProviderBranchTest` and `ContractedWordNormalizerTest`. Each failed with its fix
reverted, except the `redactUserInfo` guard, which passes on the old code by design and fails
against the suggested `\\s` change.

**Files:** [`AgentConfiguration.java`](../../src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java),
[`ScheduleConfiguration.java`](../../src/main/java/ai/labs/eddi/engine/schedule/model/ScheduleConfiguration.java),
[`IParserExtensionProvider.java`](../../src/main/java/ai/labs/eddi/modules/nlp/extensions/IParserExtensionProvider.java),
[`DamerauLevenshteinCorrectionProvider.java`](../../src/main/java/ai/labs/eddi/modules/nlp/extensions/corrections/providers/DamerauLevenshteinCorrectionProvider.java),
[`PhoneticCorrectionProvider.java`](../../src/main/java/ai/labs/eddi/modules/nlp/extensions/corrections/providers/PhoneticCorrectionProvider.java),
[`RestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java),
[`values.yaml`](../../helm/eddi/values.yaml), [`Chart.yaml`](../../helm/eddi/Chart.yaml),
[`LifecycleException.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/exceptions/LifecycleException.java),
[`ClusterConfig.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterConfig.java),
[`ContractedWordNormalizer.java`](../../src/main/java/ai/labs/eddi/modules/nlp/extensions/normalizers/ContractedWordNormalizer.java),
[`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`VaultSecretProvider.java`](../../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java),
[`AgentStoreClientLibrary.java`](../../src/main/java/ai/labs/eddi/engine/runtime/client/agents/AgentStoreClientLibrary.java),
[`CounterweightService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CounterweightService.java),
[`ClassifierGuardrail.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/impl/ClassifierGuardrail.java),
[`VaultStartupBanner.java`](../../src/main/java/ai/labs/eddi/secrets/VaultStartupBanner.java),
[`ComplianceStartupChecks.java`](../../src/main/java/ai/labs/eddi/engine/compliance/ComplianceStartupChecks.java),
[`scheduling.md`](../scheduling.md), [`semantic-parser.md`](../semantic-parser.md),
[`tool-call-guardrails.md`](../tool-call-guardrails.md), `ui/manager` agent editor and API types.

```decision-log
| 2026-11-07 | Unread `dream.batchSize`/`maxUsersPerRun` removed from the model behind `@JsonIgnoreProperties`; unread schedule `tenantId`/`allowSelfScheduling` deprecated and kept | Docs review: config fields nothing reads | Wiring them up: Dream has no multi-user sweep to batch, and schedules have no tenant model or self-scheduling gate to plug into; removing the schedule fields: they are DB columns with PUT carry-over semantics |
```
