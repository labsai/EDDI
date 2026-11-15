## 🐛 fix(manager): the Platform Operator's prompt names no unknown Qute namespace (2026-11-15)

**Repo:** EDDI (`feat/agent-health`)

### What changed and why

The backend renders the operator's system prompt as a Qute template on every turn. #1318 added the semantic parser
line "metric eddi_parser_semantic_fallback_total{outcome: match, no_match}". Qute reads `{outcome:` as a namespace
expression, and no resolver exists for `outcome`, so the whole render failed. The model was handed the raw template,
and `Operator Activation E2E` failed on `main` and on every branch merged with it.

- [`ui/manager/src/lib/operator/system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts) now says
  "metric eddi_parser_semantic_fallback_total, outcome match or no_match". Operator revision 65.
- A new test, `system-prompt.test.ts` ("the whole prompt renders as a Qute template"), checks the whole prompt in both
  scopes. It allows no `{name:` other than the namespaces EDDI registers: `vault`, `connection` and `caller`. The
  existing no-braces tests each cover only their own section, which is how this line got through. The new test fails
  with the old line.
