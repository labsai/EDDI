## feat(agent-tests): agent regression harness — golden conversations (2026-10-15)

**Repo:** EDDI (`feat/agent-test-harness`; WS6 Phase 6.1)

### What changed and why

EDDI had thousands of tests and none that asserted what an *agent* does. A config-driven engine records every turn — actions, properties, output, tool calls, cost — so a suite can assert exactly that, independently of how an LLM words its answer.

- **`AgentTestSuite` config resource** (`configs/agenttests`): a versioned document like every other config — scripted cases of turns, per-turn expectations, tool mocks, defaults (`toolMode`, `unmockedTools`, `repetitions`, `passRate`, `turnTimeoutSeconds`) and a per-run dollar ceiling (`maxCostPerRun`, default $1). `AgentTestSuiteValidator` refuses at save time (400) what a run could not honour: unknown modes, invalid or over-long regexes (500 chars), more than 200 cases / 50 turns per case / 20 repetitions / 2,000 turns per run.
- **`AgentTestRun` records** (`engine/agenttests`, collection `agenttestruns`): one document per run through `IResourceStorageFactory` at a single version (the `GroupConversationStore` pattern), so it works on MongoDB and PostgreSQL alike; listed per agent newest-first, pruned to a bounded number per agent.

**Files:** [`AgentTestSuite.java`](../../src/main/java/ai/labs/eddi/configs/agenttests/model/AgentTestSuite.java), [`AgentTestSuiteValidator.java`](../../src/main/java/ai/labs/eddi/configs/agenttests/AgentTestSuiteValidator.java), [`AgentTestSuiteStore.java`](../../src/main/java/ai/labs/eddi/configs/agenttests/mongo/AgentTestSuiteStore.java), [`AgentTestRunStore.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/AgentTestRunStore.java)
