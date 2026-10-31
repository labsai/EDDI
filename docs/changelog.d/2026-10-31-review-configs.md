## fix(configs,datastore): review findings in the configuration stores and the datastore (2026-10-31)

**Repo:** EDDI (`fix/review-configs`)

Verified CodeRabbit findings and code-review leads (§4 Persistence, §5 dead code) for `configs/**` and `datastore/**`.

### What changed and why

**Configuration stores**

- **Reverse-reference lookups no longer loop on a caller-chosen version.** `AgentStore.getAgentDescriptorsContainingWorkflow` and `WorkflowStore.getWorkflowDescriptorsContainingResource` issued two to four queries per integer version from the `?version=` the caller sent down to 1, so `?version=2000000000&includePreviousVersions=true` kept a worker busy for billions of queries. The walk now starts at the referenced resource's current version (`ResourceUtilities.clampToCurrentVersion`), or at most 1000 versions when that is unknown.
- **JSON Patch cannot relocate a credential the caller cannot read.** `ConfigJsonPatcher` applied the patch to the unredacted document, so an editor could `copy`/`move` a plaintext key into `description` and read it back. For anyone but an eddi-admin the patch now runs against a view where every credential `ConfigSecretRedactor` would redact is an opaque random token; a token is restored wherever it is a whole value, but one that ends up at another path (an `add`/`remove` earlier in an array shifts the indexes after it) is kept only if the redacted read still hides it there; a credential relocated into a readable field, or embedded in a larger string, is refused with a 400, and a `test` against a credential never sees its value.
- **JSON Patch output is bounded.** Operations are applied one by one and each `copy` is followed by a size check (nodes plus string length, counted with an early stop) that refuses growth beyond twice the stored document plus 1 MB, before anything is serialised. Shared JSON-P structure let a few copies build an exponentially large result.
- **Workflow references with a non-canonical scheme are checked.** `RestWorkflowStore.addReference` skipped any `config.uri` not starting with `eddi://`, but the engine resolves by host and id, so `Eddi://` or `x://ai.labs.llm/…` ran a configuration the access check never saw. Every URI with a scheme, host and id is now checked.
- **Parser documents check their dictionary references.** `RestParserStore` create, update and duplicate run the workflow store's reference check on the parser's extensions (VIEW on each newly introduced dictionary), so a private dictionary can no longer be reached through the standalone parse endpoint or an export.
- **Agent tests:** `maxCostPerRun` NaN/Infinity and a non-finite `expect.maxCostUsd` are refused (NaN passed the range check and disabled the budget).
- **Artifact JSON-schema validators** evaluate `pattern`/`patternProperties` through a deadline-bound regex factory sharing one 500 ms budget per validation, like the `REGEX` validator; a runaway pattern refuses the write.
- **Template migration is linear.** `TemplateSyntaxMigrator.unconvertibleReason` continues after the closer instead of re-scanning from every nested opener, and a text whose openers-times-length exceeds 50M is reported unconvertible and left alone instead of costing quadratic CPU at import or startup.
- **Global variables:** the assembled-reference check scans in one linear pass instead of a regex retried at every unterminated opener. `ApiCallExecutor` refuses a full-form `${vars:tenant/key}` that only conversation data supplied (the configured template did not write it): it would read another workspace's variable.
- **Knowledge bases:** a 6.5.0 name-layout knowledge base that drops its explicit location moves to the id layout, as a rename already did (rename-keeping-location then drop-location reached another name's default store unchecked); an Elasticsearch `indexName` must name one concrete index (no wildcard, list or exclusion); and the location collision scan fails closed when it runs past its 20,000-descriptor budget.
- **Channel integrations:** `duplicateChannel` runs the target USE check a create runs; the uniqueness checks and the write are serialised on the node; and a `channelId` a deployed agent's legacy Slack connector serves is refused instead of silently taking the route over. That check asks `ChannelTargetRouter.isServedByLegacyConnector`, which ignores the router's cached copy of standalone integrations (it lags a just-saved change by up to a minute; the store's own uniqueness check is the authority on those). The router is constructor-injected.
- **Group configurations:** `protocol.maxTurns` and a phase's `repeats` are capped at 1000, and `artifactConfig.maxArtifactsPerDiscussion` at 1000 (the quota counts a bounded listing).
- **Orphan purge:** the scan also walks the exact workflow version every current (not only deployed) agent pins, so a dependency a newer workflow version dropped is not purged from under an undeployed agent.
- **Properties:** `PropertyValues.toProperty` and `copyOf` copy nested maps and lists, so a path assignment into a property built from a cached configuration value cannot write into that shared configuration.
- **`PropertiesMigrationService`** skips only on `postgres`, like every other datastore switch, instead of running only on the literal `mongodb`.

**Datastore**

- `PostgresUserConversationStore.deleteAllForUser` throws instead of returning 0 on a SQL error, so a failed GDPR erasure step is reported as failed.
- `DescriptorStore.findByOriginId` returns every match instead of the first ten, which was a cap applied before the caller's workspace/EDIT filter and could make a merge import create a duplicate.
- Sorted PostgreSQL `findResources` pages break ties on `id`, so offset paging over equal sort values neither repeats nor skips rows (the field index still supplies the leading key; the tie is ordered by an incremental sort). MongoDB deliberately keeps the single-key sort: its field indexes are single-field, and `{field: -1, _id: -1}` would turn every sorted page into a blocking in-memory sort over all matches.
- `PostgresAttachmentStore.grantAccess` throws `AttachmentNotFoundException` for a missing blob, as the MongoDB store does.
- Attachments that record no owner fail closed for **reads**: the MongoDB store no longer lets any conversation read them, matching PostgreSQL. **Deletes are unchanged** on both backends: an ownerless blob can still be deleted by its `storageRef`, because deleting discloses nothing and refusing it would strand personal data beyond erasure. `deleteByConversation` (conversation delete, purge, GDPR erasure) is untouched and matches on the owning conversation exactly as before. Docs: [Attachments guide](../attachments-guide.md).
- `V6RenameMigration.saveDocument` addresses a string `_id` as stored (it queried `new ObjectId(string)`, which never matched) and counts a document only when the replace matched.
- Dead code removed: `datastore/model/ResourceId` and its test; the unreachable null-weight check in `AgentGroupStore.validateVotePhases` (`VoteConfig` copies weights with `Map.copyOf`, which refuses null values).

Docs updated: [RAG](../rag.md), [Group conversations](../group-conversations.md), [Attachments guide](../attachments-guide.md).

### Design decisions

- **Patch protection by token, not by path rules.** Deciding which patch operations touch credentials by their pointers breaks on array shifts and nested copies; masking the document with per-request random tokens, and admitting a token at a new path only where the redaction rules still hide it, covers both and reuses the read redaction's own rules. Over-masking (no skip paths) only restricts copy/move for non-admins.
- **Reverse-lookup clamp uses the descriptor's version** (descriptors share id and version with their resource) because the stores already hold the descriptor store; a store-level prefix query would need new queries on both backends.
- **Ownerless attachments fail closed for reads only**: the PostgreSQL store already refused those reads, and an owner nobody can establish is not an owner. Deletes stay allowed, since a delete that refuses data it cannot attribute would make it impossible to erase.

### Deferred

- Descriptor PATCH vs. sharing race (full-snapshot write conditioned only on the resource version): needs a descriptor revision or field-level update on both backends.
- Slack approval records keyed by integration name: needs an immutable integration id on approval records (integrations area).
- Knowledge-base collision check against locations of older, still-pinned versions: needs a location index across versions.
