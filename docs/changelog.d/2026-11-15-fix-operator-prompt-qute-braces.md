## 🐛 fix(operator): no literal braces in the system prompt, so Qute renders it again (2026-11-15)

**Repo:** EDDI (`fix/operator-prompt-qute-braces`)

### What broke

#1318 added `(metric eddi_parser_semantic_fallback_total{outcome: match, no_match})` to the Platform Operator's system prompt. The backend renders that prompt as a Qute template on every turn, and `{outcome: match, no_match}` reads as an expression in an `outcome` namespace that has no resolver. The render failed, so the model received the prompt unrendered and the `Operator Activation E2E` job went red on `main`.

The PR's own CI did not stop it: that job is not a required check, and no unit test looked for namespace-style braces in the whole prompt.

### What changed

- `ui/manager/src/lib/operator/system-prompt.ts`: the metric is written as `eddi_parser_semantic_fallback_total, tagged outcome=match or no_match`, with no braces.
- `ui/manager/src/lib/operator/__tests__/system-prompt.test.ts`: a guard test builds the whole prompt body (read-only and read-plus-write grants) and fails on any `{name: ...}` whose namespace is not `vault`, `connection` or `caller`, so this fails a unit test instead of only the e2e job.
- `operator-revision.json`: revision 64 to 65 (the prompt text changed), new fingerprint.

### Design decisions

- The guard checks the namespace pattern rather than banning all braces: the prompt legitimately carries `{#if context.screen}`, `{context.x}` and a few literal `{...}` examples that Qute leaves alone.
