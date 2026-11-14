## 🐛 fix(rules): accept legacy tagMatches and reference-less dependencies on load, refuse them on save (2026-11-14)

**Repo:** EDDI (`fix/rules-legacy-tagmatches-on-load`)

### What broke

#1337 made `RuleDeserialization` refuse a `deploymentContext` condition with a non-blank `tagMatches`, and a `dependency` without a `reference`. The same deserializer runs on save (`RuleSetStore.validate`) and on deploy (`RulesEvaluationTask.configure`), and import and sync write through the same store. A ruleset stored before the upgrade therefore stopped the whole agent from deploying, not just the one rule, and an exported agent could no longer be imported. There was no migration.

The PR said removing the field changes nothing at runtime. That is wrong for a negated condition. `negation { deploymentContext { tagMatches: "x" } }` was always SUCCESS, because the inner condition always failed, so such a rule fired on every turn. Removing only `tagMatches` makes the inner condition match on `when` alone, which flips the rule.

### What changed and why

- **Retired configs are a separate check.** `IRuleCondition.retiredConfiguration()` (default `null`) reports a config that a new ruleset may not be saved with but that stored rulesets keep. `DeploymentContextCondition` and `Dependency` report their case there instead of throwing from `validateConfiguration()`.
- **Load accepts, save refuses.** `IRuleDeserialization.deserialize(json, rulesetName)` reads a stored ruleset. A retired config is accepted and logs a WARN naming the ruleset URI and the rule. `validateForSave(json)` runs the same checks and also refuses a retired config, with the message #1337 added. `RulesEvaluationTask.configure` uses the first. `RuleSetStore.validate` uses the second, so a REST or MCP create or update is refused as before.
- **Existing rulesets written through the store.** `ExistingRuleSetWrite` is a `ScopedValue` marking a write of a ruleset that exists already: the import's ruleset create and merge update (`RestImportService`), the sync/upgrade create and update (`UpgradeExecutor`), and `RestRuleSetStore.duplicateRuleSet`, which agent duplication uses. Inside it `RuleSetStore.validate` treats a retired config the way deploy does. Every other save-time check still applies.
- **Old runtime semantics kept exactly.** With `tagMatches` set, `DeploymentContextCondition.execute` returns FAIL. That is what the old tag lookup always returned, because nothing writes `agent:tags`. `Dependency` with no `reference` matches no rule and fails, and it no longer throws a NullPointerException on the null reference.
- **Docs.** `behavior-rules.md` (Dependency, Deployment Context) and `upgrading-from-6.5.md` now say that stored configs are accepted with a WARN and refused only on save, and warn that a negated `tagMatches` changes meaning when the field is removed. The 2026-11-07 fragment's "changes nothing at runtime" sentence is corrected.

### Design decisions

- The scope marks the *existing-ruleset* writes, not the author's saves. The REST store is shared by both: MCP and the setup service call it in-process, and so do the import's merge update and the sync. Strict is the default, so a new write path stays strict unless someone opts it in.
- The WARN names the ruleset URI and the rule, but not the agent. Workflows, and the rulesets in them, are configured and cached per workflow and can be shared by several agents, so `configure()` does not know which agent it is serving.
- A `ScopedValue`, not a `ThreadLocal`, matching `TemplateDataScope`. The binding cannot outlive the call, and every wrapped write runs on the caller's thread.

### Tests

`RuleDeserializationTest`, `DeploymentContextConditionTest`, `DependencyTest`, `RuleSetStoreSaveTimeValidationTest`, `RulesEvaluationTaskTest`, `RestImportServiceReviewFixesTest`. They cover the following: a stored `tagMatches` ruleset deploys with a WARN; a negated `tagMatches` still evaluates SUCCESS; the same ruleset is refused on create and update; it is accepted inside `ExistingRuleSetWrite` while other checks still refuse; a ZIP import through the real `RuleSetStore` succeeds; a reference-less `dependency` loads and fails.

**Files:** [`ExistingRuleSetWrite.java`](../../src/main/java/ai/labs/eddi/configs/rules/ExistingRuleSetWrite.java), [`RuleSetStore.java`](../../src/main/java/ai/labs/eddi/configs/rules/mongo/RuleSetStore.java), [`RestRuleSetStore.java`](../../src/main/java/ai/labs/eddi/configs/rules/rest/RestRuleSetStore.java), [`IRuleDeserialization.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/IRuleDeserialization.java), [`RuleDeserialization.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/RuleDeserialization.java), [`RulesEvaluationTask.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/RulesEvaluationTask.java), [`IRuleCondition.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/IRuleCondition.java), [`DeploymentContextCondition.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/DeploymentContextCondition.java), [`Dependency.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/Dependency.java), [`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java), [`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java), [`behavior-rules.md`](../behavior-rules.md), [`upgrading-from-6.5.md`](../upgrading-from-6.5.md)
