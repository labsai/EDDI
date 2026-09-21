## 🐛 fix(ui): the agent wizard could only produce a Jlama agent that never loads (2026-09-20)

**Repo:** EDDI (`fix/manager-jlama-wizard`)

Every Jlama path through the Manager's agent wizard produced an agent that failed on
its first message. Three defects, all of the same shape — the wizard offering a value
the backend cannot use, and reporting success anyway.

### What was wrong

1. **The model suggestions were not resolvable.** `MODEL_SUGGESTIONS.jlama` offered
   `llama-3.2-1b` and `tinyllama`. Jlama resolves a model through `JlamaModelRegistry`,
   which downloads it from Hugging Face and wants an `owner/name` repository id. A bare
   name has no owner to look up, so the download fails on the agent's first turn — long
   after the wizard said "created". `LLM_PROVIDERS`'s `defaultModel` carried the same
   bare name, which is what the user sees as the model placeholder before typing.
2. **A `baseUrl` field for a provider with no endpoint.** The wizard offered Jlama a
   Base URL with a `http://localhost:8080` placeholder. Jlama runs *in-process* inside
   the EDDI JVM; `AgentSetupService` drops `baseUrl` for `jlama` before the builder is
   reached, and `JlamaLanguageModelBuilder.recognisedParameters()` does not contain it
   either. Anything typed there vanished with no visible trace.
3. **…and the field was marked required.** `isBaseUrlRequired` returned true for
   `jlama`, which in `operator-activation.tsx` is a hard gate (`modelStepValid`): an
   admin activating the Platform Operator on Jlama could not proceed without filling in
   a field whose value was then thrown away.
4. **A hidden field still submitted its stale value.** A consequence of fixing (2):
   with the field no longer rendered, a base URL typed for a previous provider stayed
   in wizard state and was still sent, with no way for the user to see or clear it.
   `handleProviderChange` now clears it — in the wizard and in operator activation —
   when the incoming provider has no endpoint.

Worth noting that the *rule-based* reference config in `docs/agent-configs/` already had
this right — its Jlama chooser offers `tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4`. The
Manager wizard was the outlier.

### What changed

**Manager (`ui/manager`)**

- `src/lib/model-suggestions.ts` — Jlama now suggests only ids this repository already
  treats as real: `tjake/Llama-3.2-1B-Instruct-JQ4` (from `docs/langchain.md`) and
  `tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4` (from the shipped reference config). Any
  other `owner/name` repo can still be typed.
- `src/lib/model-suggestions.ts` — new `supportsBaseUrl()` backed by an
  `IN_PROCESS_PROVIDERS` set; `isBaseUrlRequired()` no longer returns true for `jlama`.
- `src/pages/agent-wizard.tsx` — the Base URL block is omitted entirely when
  `supportsBaseUrl` is false, and a Jlama note replaces it: no endpoint, weights come
  from Hugging Face on first use, and the tuning parameters worth setting afterwards.
  The model hint becomes Jlama-specific (the `owner/name` requirement).
- `src/lib/api/agent-setup.ts` — `defaultModel` is a real repo id; the provider label
  is "Jlama (In-Process)" rather than "Jlama (Local)", which read like Ollama.
- Four new i18n keys across all 11 locales.

**Backend**

The five deployment parameters the wizard now points users at did not exist yet —
surfacing them without adding them would have been a fresh instance of the same bug.
`JlamaLanguageModelBuilder` now reads `modelCachePath`, `threadCount`,
`quantizeModelAtRuntime`, `workingDirectory` and `workingQuantizedType`, all of which
`JlamaChatModel.builder()` has always accepted. `modelCachePath` is the one that
matters operationally: its default is `~/.jlama/models`, which in a container is the
ephemeral writable layer, so every pod restart re-downloads multiple gigabytes.

`ModelParameterValues` gains `applyPath`, following the existing lenient-read
convention — an unusable value is logged and skipped so the model default stands,
rather than throwing out of the build path and failing every conversation the agent
serves.

### Design decisions

- **Omit the field rather than disable it.** A disabled or ignored Base URL input still
  tells the reader an endpoint exists. For an in-process provider it does not, so the
  field is not rendered at all — the same reasoning the group-collaboration configs use
  for tools that are never assembled (root `AGENTS.md` §4.2).
- **Only ship model ids the repo already vouches for.** A longer catalogue would have
  meant guessing at Hugging Face repo names, which is exactly the failure being fixed.
  The hint text carries the `owner/name` rule so a user can supply their own.
- **`workingQuantizedType` is parsed case-insensitively** and an unknown value keeps
  Jlama's default. `q4` is what a user writes; `Q4` is what the enum calls it.
- **`jlama-core` stays undeclared in `pom.xml`.** `DType` is imported from it
  transitively via `langchain4j-jlama`; pinning a second version of a library the
  langchain4j artifact already manages would be the worse hazard.

### Files

- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/JlamaLanguageModelBuilder.java`
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/ModelParameterValues.java`
- `src/test/java/ai/labs/eddi/modules/llm/impl/builder/LanguageModelBuildersTest.java`
- `src/test/java/ai/labs/eddi/modules/llm/impl/builder/ModelParameterValuesTest.java`
- `docs/langchain.md` — the Jlama section now states the `owner/name` requirement, that
  there is no `baseUrl`, and documents the five parameters in a table
- `ui/manager/src/lib/model-suggestions.ts`
- `ui/manager/src/lib/api/agent-setup.ts`
- `ui/manager/src/lib/api/operator.ts` — stale "(Ollama, Jlama)" doc comment
- `ui/manager/src/components/operator/operator-activation.tsx`
- `ui/manager/src/pages/agent-wizard.tsx`
- `ui/manager/src/lib/__tests__/model-suggestions.test.ts` (new)
- `ui/manager/src/pages/__tests__/agent-wizard.test.tsx`
- `ui/manager/src/i18n/locales/*.json` (11 files)

### Verification

- `./mvnw compile` clean; `LanguageModelBuildersTest` + `ModelParameterValuesTest` — the
  new Jlama and `applyPath` cases pass (the pre-existing failures in that class are the
  sandbox's "Unable to establish loopback connection", not this change).
- Repo-wide guards green: `ImportStyleTest`, `DocumentationLinksTest`,
  `StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`,
  `ChangelogRotationTest`, `BuildQualityGatesTest`.
- Manager: `npm run typecheck`, `npm run lint`, `npm run i18n:check`, `npm run build`
  and the full suite (413 files, 6556 tests) all pass.
- **The six new UI tests were mutation-checked.** Reverting the suggestions, the
  default model or `IN_PROCESS_PROVIDERS` fails the five that pin the wizard's Jlama
  behaviour; dropping the `handleProviderChange` clear fails the sixth. They would
  have caught this.

---
