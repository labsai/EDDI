## Full Java comment review: every file in src/main/java (2026-11-07)

**Repo:** EDDI · **Branch:** `docs/javadoc-full-review` (stacked on `docs/documentation-review`)

The earlier documentation review swept Javadoc by package and only partly covered it. This pass reads all of `src/main/java`, 1,811 files and about 6.7 MB of comments. The files were split into 24 slices of equal comment volume and each slice went to its own reviewer. Every comment line, Javadoc block and documentation string was checked against the code it describes: OpenAPI `@Operation`/`@Parameter`/`@APIResponse`, langchain4j `@Tool`/`@P`, MCP `@Tool`/`@ToolArg`, and `ExtensionDescriptor` text. Wrong comments were fixed. Four slices had read their largest files through a filtered comment view, and those 132 files went to eight more reviewers for a full read.

**What changed:** about 400 comment fixes across 384 files. A lexer-level comparison that strips comments from the old and new sources shows that no code changed: a draft also corrected 31 files' description and message strings, but those were taken out again in review (see below), so the branch changes comments only. Typical fixes:

- Javadoc that had drifted onto the wrong member (onto a constant, a field, or stacked on another block) moved back to the member it describes.
- `{@link}`/`@see` targets that no longer exist, or that named the wrong signature.
- Stale names:
  - `AgentOrchestrator.*` methods now in `ToolLoopRunner`, `ToolObjectReflector`, `ToolApprovalGateSupport` and the `*ToolsProvider`s;
  - `ConversationCoordinator`, `SafeHttpClient`, `dataFactory.createData`;
  - `@DefaultBean`/`@LookupIfProperty` wording where `DataStoreProducers` now selects the store.
- Wrong defaults, limits and counts, for example:
  - the tool sources now number eight with `team`;
  - Slack retries on 429/500/502/503/504;
  - the connection cache TTL is one minute, not five;
  - there are seven discussion presets, not six;
  - the `PostgresStartupSchemaLock` key decodes to `EDDISCHG`.
- Stale vocabulary: package→workflow and "LangChain"→LLM in comments.
- `@since 6.6.0`→`6.7.0` on the alert stores, which first shipped in tag 6.7.0.

**Strings left for a follow-up.** The draft also rewrote OpenAPI `@Operation`/`@APIResponse` descriptions, MCP `@Tool`/`@ToolArg` descriptions, langchain4j `@P`/`@Tool` texts, and log and error messages in 31 files (for example `list_conversations` listing all six states, `approve_group_phase` naming `taskApprovalsJson`, `delete_user_data` listing what the cascade deletes, the team-cadence 400 message naming `/groupstore/groups/{groupId}/workspace/cadences`). Those strings are code: the model and API clients read them. The review took them out so that this branch stays comment-only, which a lexer comparison against `main` now proves. They belong in their own PR.

**Review fixes (CodeRabbit was waived by the repository owner for this PR, which it skips as too large):** comments that the rewrite got wrong or left incomplete. `ConversationHitlService` (it never calls `say` on the facade), `GroupHitlCoordinator` (`planHashStrict`), `HitlConfigValidation` (pause-reason length, `maxRevisionsPerTurn` range), `GdprComplianceService` (where the conversation ids are resolved, and which steps use them), `UserDataExport` (attachment metadata covers all of the user's conversations), `SharedKvCache` (RAG ingestion statuses), `NatsConnectionManager`, `JetStreamEventBus` (`CACHE_CLEAR` lane), `ClusterConversationCoordinator` (when the forwarder runs), `HighValueSurfaceGuard` (the k8s auth overlay and Helm), `DisabledAuthController` (`authorization.enabled` follows OIDC), `VaultMasterKeyStrength` (`eddi.vault.allow-weak-master-key`), `GroupSigningGuard` (WARN versus ERROR), `DirectoryUser`, `IRestResourceSharing`, `RestAuditStore`, `RemoteApiResourceSource` (no plain `RuntimeException` exists), `GroupConversationEventSink` (nothing produces an award decision yet), `LegacyDocumentMigrations` (import skips the memory transform), `EmailOwnerNotifier` and `SecretValueScrubber`.

**Merged with `main` (2026-11-17).** Files only the stacked docs commit touched take `main`'s final #1342 version. In the 14 conflicting Java files `main`'s code was kept exactly, with this branch's comment only where it still describes that code. Comments `main`'s newer code had made stale were corrected in `Conversation` (eight bookmark fields), `TeamCadenceService`, `TaskForceEngine`, `GroupConversation`, `RestImportService`, `RagIngestionService`, `UpgradeExecutor`, `PiiRedactionGuardrail` (it also runs on tool-call arguments) and `DreamService`. With comments stripped, the branch's Java is identical to `main`'s.

**Left alone:**
- The LLM task's `"Lang Chain"` descriptor label and a "package" log line in `RestAgentStore`, both asserted by tests.
- The `DeploymentContextCondition` comments, which labsai/EDDI#1337 rewrites.

**Code bugs found by this pass** are fixed test-first on the separate branch `fix/docs-review-findings`, not here:
- `LifecycleInterruptedException` drops its cause.
- The `ClusterConfig.redactUserInfo` regex uses `"\s"`, which in a Java string is a literal space, not "any whitespace".
- `ContractedWordNormalizer` expands "won't" to "would not".
- `RemoteApiResourceSource` returns 500 instead of 502 for an unlisted agent.
- The vault reset hint omits `?confirm=true`.
- `AgentStoreClientLibrary` uses the wrong logger category.
- `CounterweightService` never reaches its unknown-level warning.
- Suspected, still to be checked: `PostgresHealthCheck` activation on MongoDB deployments, and a layout inconsistency in the classifier guardrail's `TOOL_CALL` phase.
