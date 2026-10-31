## fix(modules): pipeline-module review findings (2026-10-31)

**Repo:** EDDI (`fix/review-modules`)

### What changed and why

Fixes from the code review and the CodeRabbit sweep over the pipeline modules
(`nlp`, `apicalls`, `properties`, `templating`, `rules`, `mcpcalls`).

**apicalls**

- A delayed HTTP call (`preRequest.delayBeforeExecutingInMillis`, retry backoff) no longer hands its blocking send to the single shared `eddi-scheduler` thread. The delay is served on the calling turn thread, so one slow request cannot queue every delayed call in the node behind it, and an interrupted wait sends nothing instead of leaving a queued send to fire later.
- Vault references are found with the linear `SecretReference.scanner()` (request building) and a new linear scanner in `ConfigReferenceGuard`, instead of regex `find()` loops that rescanned the remaining text at every unterminated `${vault:` opener (quadratic work on a long input).
- A vault secret resolved into the URL, body or a query value now disables redirect following, as a header secret already did; the redirect handler would otherwise replay the body on a 307/308 to a host the secret's `allowedHosts` never judged.
- The malformed-JSON warning no longer logs a resolved credential that the parser quoted in its message.

**nlp**

- `POST /parser/{parserId}` rejects an id that is not a store id (hex or UUID) before authorizing it. An id such as `<other>?version=3&x=` was authorized as one resource and resolved to another.
- The merged-terms and Damerau-Levenshtein corrections skip tokens over 100 characters (`ICorrection.MAX_CORRECTABLE_LENGTH`): their cost is quadratic in the token length and the parser limits the number of tokens, not their length.
- `PunctuationNormalizerProvider` no longer stores one parser's `punctuationRegexPattern` in a singleton field that became the default of every parser built afterwards. `PunctuationNormalizer` matches under the same 200 ms regex budget as dictionary regexes, because the pattern can leave its character class.

**properties**

- `scope: "secret"` with `toObjectPath` is rejected on save and at run time (the copy returned before any vaulting). A secret instruction whose value is converted to an object and refused now scrubs the raw input first. Properties extracted earlier in the turn can no longer put a vaulted plaintext back or overwrite the new vault reference. The live output scrub is deep, so a credential in a typed output item (an LLM reply) is removed.

**rules**

- `dependency` conditions evaluate a rule once per evaluation instead of once per path to it; a chain of rules that each depend twice on the previous one cost 2^n evaluations.
- `connector` `operator` is case-insensitive, defaults to `AND` (as documented) and an unknown value is refused at validation instead of silently becoming `OR`; `getConfigs()`/`clone()` can no longer throw on a connector without one.
- Removed `Rule.clone()`: unused, and it dropped the rule's actions.

**mcpcalls**

- An MCP call without a `retry` block now makes a single attempt (it used to fall through to the shared three-attempt default, so a side-effecting tool could run three times), and retries stop when the turn deadline leaves no room.

**templating**

- `str:concat` and `str:join` are measured before the generated resolver builds them, so reusing one permitted value many times can no longer allocate past `eddi.templating.max-output-chars`.
- Template preview redacts the `snippets` namespace for restricted callers whatever `getAll()` returns.

### Not changed (decisions)

- `SecretPropertyVault` still reads the `tenantId` conversation property: the guard reads the same property and multi-tenancy is not implemented yet; deriving the tenant from trusted context is a product decision.
- `CapabilityMatchCondition` stays unfiltered: rules run off the request thread, where the access guard has no identity to filter by.
- Quick-reply expression values from an HTTP response are not escaped (needs an expression-escaping design); a per-parse regex deadline for `RegularDictionary` and a cumulative loop-result budget in the template engine are left as defense-in-depth follow-ups.
- The dotted-path walkers in `OutputMatcher` and `ResponseShapeValidator` are not replaced by `PathNavigator`: its arithmetic-expression fallback and array syntax differ from their strict lookups, and both live outside this area.

**Files:** [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`ConfigReferenceGuard.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceGuard.java), [`RestSemanticParser.java`](../../src/main/java/ai/labs/eddi/modules/nlp/impl/RestSemanticParser.java), [`PropertyInstructionExecutor.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertyInstructionExecutor.java), [`Dependency.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/Dependency.java), [`Connector.java`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/Connector.java), [`McpCallsTask.java`](../../src/main/java/ai/labs/eddi/modules/mcpcalls/impl/McpCallsTask.java), [`BoundedFormat.java`](../../src/main/java/ai/labs/eddi/modules/templating/impl/BoundedFormat.java)
