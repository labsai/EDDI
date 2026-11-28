## fix(hitl): GuardText also covers langchain4j's fallback argument parse (2026-11-14)

**Repo:** EDDI (`fix/hitl-guardtext-langchain4j-fallback`)

### What changed and why

#1362 (`c7c2ff8b3c`) replaced `ApprovedCallScope`'s single-pass `jsonUnescaped` with `GuardText.haystack`, which un-escapes only what lenient Jackson parses. That assumed no receiver executes text Jackson rejects. langchain4j does: when its first parse fails, `ToolExecutionRequestUtil.argumentsAsMap` (the path `DefaultToolExecutor` takes for built-in tools such as `converse_with_agent`) strips one leading and one trailing `"`, turns every `\"` into `"`, drops trailing commas, and parses again. Two argument strings with the conversation id's first character written as a JSON unicode escape were therefore refused before #1362 and allowed after it, while langchain4j still executed them with the agent's own conversation id:

1. the document with every quote written as `\"` and no outer quotes;
2. the document wrapped in one pair of outer quotes, its inner quotes left bare.

That reopened the absolute rule that an agent may not `converse_with_agent` into its own running conversation, on resume and in the live tool loop (`ToolLoopResumer.argumentsTargetConversation`). `SelfUngatingGuard.refusalFromArguments` had the same gap before #1362 for the acting agent's id and the gate keys (`toolApprovals`, `toolCallGuardrails`, `hitlConfig`).

`GuardText.haystack` is now a union of readings, for the raw text and for its percent-decoded form: (a) the lenient Jackson parse, as before; (b) the old single-pass `jsonUnescaped`, whether or not the text parses; (c) a lenient parse of langchain4j's normalised form, using the three patterns copied from `ToolExecutionRequestUtil` in langchain4j 1.23.0 (read from the jar's bytecode, not guessed). Each reading only appends text, so the haystack can only grow: nothing #1362 caught is lost, and everything the old `ApprovedCallScope` caught is caught again. Percent-decoding and `Locale.ROOT` lower-casing are unchanged.

### Behaviour

Stricter only. Both guards now refuse the two shapes above when they name the own conversation, the acting agent or a gate key. No config, endpoint, MCP tool or default changed.

### Tests

- `GuardTextTest.Agreement.bothSeeTheId`: both shapes plus a trailing-comma variant; `SelfUngatingGuard` and `ApprovedCallScope` must each see the id.
- `ToolLoopResumerSelfConversationTest`: for both shapes, langchain4j's own `argumentsAsMap` reads the agent's conversation id, and `argumentsTargetConversation`, the live path and the resume path all refuse; the same shapes naming another conversation pass.
- `SelfUngatingGuardProtectionTest.Langchain4jFallbackShapes`: both shapes refused for an escaped acting-agent id and escaped `toolApprovals`, `toolCallGuardrails` and `hitlConfig` keys; another agent with no gate key still runs.

**Files:** [`GuardText.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/GuardText.java), [`GuardTextTest.java`](../../src/test/java/ai/labs/eddi/engine/hitl/tools/GuardTextTest.java), [`SelfUngatingGuardProtectionTest.java`](../../src/test/java/ai/labs/eddi/engine/hitl/tools/SelfUngatingGuardProtectionTest.java), [`ToolLoopResumerSelfConversationTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumerSelfConversationTest.java)

### Next

The normalisation mirrors langchain4j 1.23.0. A langchain4j upgrade that changes `ToolExecutionRequestUtil` fails `ToolLoopResumerSelfConversationTest`, which calls it directly, and the patterns in `GuardText` must then be re-checked.
