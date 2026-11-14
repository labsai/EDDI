## Full Java comment review: every file in src/main/java (2026-11-07)

**Repo:** EDDI · **Branch:** `docs/javadoc-full-review` (stacked on `docs/documentation-review`)

The earlier documentation review swept Javadoc by package and only partly covered it. This pass reads all of `src/main/java`, 1,811 files and about 6.7 MB of comments. The files were split into 24 slices of equal comment volume and each slice went to its own reviewer. Every comment line, Javadoc block and documentation string was checked against the code it describes: OpenAPI `@Operation`/`@Parameter`/`@APIResponse`, langchain4j `@Tool`/`@P`, MCP `@Tool`/`@ToolArg`, and `ExtensionDescriptor` text. Wrong comments were fixed. Four slices had read their largest files through a filtered comment view, and those 132 files went to eight more reviewers for a full read.

**What changed:** about 400 comment fixes across 384 files. A lexer-level comparison that strips comments from the old and new sources shows that the only non-comment changes are 22 description or message strings, so no behaviour changed. Typical fixes:

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
- Stale vocabulary: package→workflow in OpenAPI and MCP descriptions, and "LangChain"→LLM.
- MCP tool descriptions:
  - `list_conversations` lists all six conversation states;
  - `approve_group_phase` names `taskApprovalsJson`;
  - `delete_user_data` lists what the cascade really deletes.
- `@since 6.6.0`→`6.7.0` on the alert stores, which first shipped in tag 6.7.0.

**Runtime strings changed:**
- `McpConversationTools`: the invalid-state error message.
- `RestScheduleStore`: the team-cadence 400 message now names the real `/groupstore/groups/{groupId}/workspace/cadences` path.
- `ConversationSummarizer`: an info log line.
- `RestChannelIntegrationStore`: the plaintext-secret warning.
- `VertexGeminiLanguageModelBuilder`: the Gemini 3 warning. google-cloud-vertexai 1.48.0's `Part` does carry `thought_signature`; langchain4j's mapper drops it.

No test asserts the old text. The Manager's OpenAPI snapshot stores only method and path.

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
