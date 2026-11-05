## 🧪 test: raise branch coverage back over the merged 80% gate (2026-11-05)

**Repo:** EDDI (`test/raise-branch-coverage`)

### Why

After the last day's feature merges (cost accounting, team handoff, the SERVICE judge, HITL
edit-on-approve and approval-card context, setup-api, argument schemas, the configs/llm/secrets
fixes), the `Integration Tests` job on `main` failed the JaCoCo `merged-check`:
branches covered ratio 0.79 against a minimum of 0.80. The threshold stays where it is; this
branch adds tests.

### What changed

Test code only — no production source is touched. Plain JUnit 5 + Mockito, no `@QuarkusTest`.
Every test asserts behaviour (refusal messages, routing decisions, masking, audit units), not
just execution. Classes covered, grouped by the PR that introduced or changed them:

- **Argument schemas (#1268):** `ToolArgumentSchemaValidator` (keyword reduction, nullable type
  lists, the violation cap, violation wording), `OpenApiArgumentSchemas` (no direct test before),
  `McpApiToolBuilder` helpers (unsendable inputs, header-answering operations, server variables).
- **Team handoff (#1223):** `TeamHandoffToolsProvider` — the standalone filed ledger in step data,
  artifact references, unusable targets, participant rules.
- **Cost accounting (#1222):** `MeteredTokenUsage`, `PricingLint` (cascade, judge, summary and
  tool-summarizer pricing), `UnpricedSightings`.
- **HITL (#1228, #1230):** `HitlDecisionRecords`, `EditMetrics`, `ApprovalCategories`,
  `GroupApprovalContextRenderer`, `HitlNotificationContent`.
- **Cluster rate limits:** `ToolRateLimiter` in NATS mode — shared buckets across replicas, the
  global layer giving the conversation its token back, degraded local-share and reject.
- **Elsewhere:** `ArtifactSchemaComplexity`, `EmailAgentToolsInspector`, `EmailTargetRouting`,
  `EmailReplyThread`, `TurnBudgetValidator`, `TurnObservation`, `ResourceSharingService.describe`,
  `SpaceTenants`, `JsonBodyErrors`, `AlertPayloads`, `DeliveryTarget`, `OutboundSettings.fromConfig`,
  `AttachmentClassifier`, `WebhookEventParser`, `PropertyAccessValueResolver`, `IngestionSource`
  validation, `EmbeddingParameters`, `ChatCompletionRequest`.

### Measured

Unit suite only (integration tests need Docker): branch ratio **0.7793 → 0.7915**, +867
branches. The merged unit + IT ratio could not be measured locally; CI's `Integration Tests` job
is the check.

### Noticed, not changed

`JsonBodyErrors` renders an unknown field's JSON path including the field itself
(`Unknown field 'x' at x.`): it calls `StrictConfigurationParser.jsonPath(e)`, which renders the
whole path, where the private overload documents "one short of it for an unknown field". The new
test asserts only the prefix of the message, so it does not pin either form.
