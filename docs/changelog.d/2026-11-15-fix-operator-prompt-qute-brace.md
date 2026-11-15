## Platform Operator prompt: no Qute namespace in the parser metric line (2026-11-15)

**Repo:** EDDI · **Branch:** `fix/operator-prompt-qute-brace`

#1318 described the semantic-fallback metric in the Operator's system prompt as
`eddi_parser_semantic_fallback_total{outcome: match, no_match}`. The prompt is a
Qute template on the server, and `{outcome:…}` is a namespace expression with no
resolver, so the whole render failed (`LlmTask`: "No namespace resolver found for
[outcome]") and the model received the prompt unrendered. `Operator Activation E2E`
failed on main from `5bba2dcf4a` onward, and on every PR that merged main.

- `ui/manager/src/lib/operator/system-prompt.ts`: the line now reads "tag outcome =
  match or no_match", without braces.
- `ui/manager/src/lib/operator/__tests__/system-prompt.test.ts`: a guard builds both
  scopes' prompts and fails on any `{name:` whose namespace is not one EDDI
  registers as a pass-through (`vault`, `eddivault`, `connection`, `context`,
  `vars`, `caller`). It fails on the prompt as #1318 left it.
- `operator-revision.json`: revision 65, since the prompt changed.
