## 🐛 fix(llm): a configuration reference in an LLM parameter no longer breaks its templating (2026-09-21)

**Repo:** EDDI · **Branch:** `fix/llm-config-reference-templating`

`LlmTask` runs the Qute engine over every LLM parameter before the model is built, and escaped
`${vault:...}` mentions so a prompt could document the syntax. The other three reference namespaces —
`vars`, `connection`, `caller` — were not escaped, although they are resolved the same way: AFTER
templating, by `ChatModelRegistry`/`SecretResolver`, with deliberately no Qute namespace resolver.

So a parameter carrying one of them threw on every turn. Observed on a live deployment, where an agent
sets `"modelName": "${vars:gemini-model}"` so one global variable drives every agent's model:

```
ERROR Template processing failed for LLM parameter 'modelName':
      No namespace resolver found for [vars] in expression {vars:gemini-model}
```

The turn still worked — the catch keeps the parameter's RAW value, which is exactly right when the value
is *only* a reference, and the registry resolves it afterwards. Two things were wrong anyway:

- an ERROR per turn per such parameter, which buries real errors in the log;
- a reference sitting **beside** a real expression abandons the whole render, so `{properties.x}` next to
  it reaches the model as literal text. Silent, and wrong.

`escapeVaultMentions` becomes `escapeConfigReferenceMentions` and now covers `vault`, the legacy
`eddivault`, `vars`, `connection` and `caller`. Httpcall templating is untouched and still fails loudly
there, as the `CallerNamespaceResolver` security decision requires.

**Tests:** `LlmTaskVaultMentionTest` — one case per namespace (each asserting the un-escaped form still
throws, so the guard cannot go vacuous), the legacy prefix, and the reference-beside-expression case.
Narrowing the pattern back to `vault` fails 5 of them with the exact production message.

The escape has two halves that can drift: the regex, and a cheap `contains` pre-check that decides whether
the regex runs at all. The pre-check list deliberately omits the legacy `eddivault:`, which is only covered
because `"eddivault:"` contains `"vault:"` — correct, but invisible, and a namespace added to the regex
alone would silently keep crashing. `preCheckCoversEveryNamespaceInThePattern` derives the namespaces from
the pattern instead of restating them and asserts each round-trips, so the halves cannot diverge unnoticed.
Mutation-checked both ways: dropping `"vault:"` from the pre-check fails 5 tests; adding a namespace to the
pattern alone fails that guard and only that guard.

---
