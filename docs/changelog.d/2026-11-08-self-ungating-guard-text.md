## refactor(hitl): one GuardText for the self-ungating heuristics (2026-11-08)

**Repo:** EDDI (`refactor/self-ungating-guard-text`)

### What changed and why

The two server-side guards around an approved tool call decided "does this request name an id?" with two different decodings. `SelfUngatingGuard` percent-decoded the raw arguments and also walked them as parsed JSON, a few levels into JSON-in-a-string. `ApprovedCallScope.targetsConversation`, which backs the self-conversation rule, percent-decoded the text and ran a hand-written JSON unescape over it. Given the same arguments, the two disagreed, and each one missed spellings the other caught:

- **Self-conversation rule missed:** an id inside a document carried as a JSON string (escaped once per level, so one unescape pass leaves the escape in place), and a JSON escape that was itself percent-encoded (`%5Cu0034`).
- **Self-ungating rule missed:** an escaped id in arguments that only a lenient parser accepts (single quotes, for example, or a leading comment). Its strict parse failed, or never started because the text did not begin with a bracket or a quote, so only the raw-text test ran.

Both now go through one package-private `GuardText` in `engine/hitl/tools`. It has three parts. `decodeLower` percent-decodes and lower-cases, and keeps the raw text when an escape is malformed. `containsId` does a trimmed, case-insensitive containment test. `haystack` builds the decoded raw text plus every key and string value from a JSON walk, and follows nested JSON strings up to four levels. The walk parses with every `JsonReadFeature` enabled: a parse can only add text to the haystack, so the most lenient parse is the safe one. It parses any text that starts with a character such a document can start with, comments (`/*`, `//`, `#`) and single quotes included, not only `{`, `[` and `"`. `ApprovedCallScope.jsonUnescaped` and `urlDecoded` are deleted. The lenient parse covers what `jsonUnescaped` covered for non-strict JSON. The only thing it no longer catches is unparseable text such as truncated JSON, and no receiver accepts that either.

Also fixed: `SelfUngatingGuard.collectNonEmptyKeyPaths` carried two stacked Javadoc blocks. The first one described `collectKeyPaths`, which had none, so it now sits on that method.

### Behaviour

Behaviour only gets stricter, in two places. The self-conversation rule (on resume and in the live tool loop through `ToolLoopResumer.uriTargetsConversation`) now refuses the two spellings listed above. The raw-argument self-ungating test now refuses escaped ids and keys in lenient JSON, including JSON that starts with a comment. No config, endpoint, MCP tool or default changed.

### Tests

`GuardTextTest` (new). Decoding: malformed percent escapes, a JSON-escaped key, the nested-string depth bound, non-JSON text and `containsId`. Agreement: `SelfUngatingGuard.refusalFromArguments` and `ApprovedCallScope.targetsConversation` must both see an id in seven escaped or encoded spellings, and both must miss another id. Before the change, 5 of the 7 spellings failed, in both directions. The existing `SelfUngatingGuard*Test`, `ApprovedCallScopeTest` and `ToolLoopResumerSelf*Test` pass unchanged.

**Files:** [`GuardText.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/GuardText.java), [`SelfUngatingGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/SelfUngatingGuard.java), [`ApprovedCallScope.java`](../../src/main/java/ai/labs/eddi/engine/hitl/tools/ApprovedCallScope.java), [`GuardTextTest.java`](../../src/test/java/ai/labs/eddi/engine/hitl/tools/GuardTextTest.java)

### Next

`ToolLoopResumer.argumentsTargetConversation` still runs its own JSON walk (`appendStrings`) after `uriTargetsConversation`. `uriTargetsConversation` already searches the full haystack, so that second pass is now redundant and can be removed. It was left in place because this PR is scoped to the two guard classes. Splitting the JSON-pointer and patch helpers out of `SelfUngatingGuard` into a `PatchGate` remains an optional follow-up.
