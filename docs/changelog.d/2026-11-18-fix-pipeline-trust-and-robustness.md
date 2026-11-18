## 🔒 fix(properties): a client can no longer set conversation properties through context (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

A context entry whose key starts with `properties` and whose type is `expressions` was parsed by the property step of every agent, after the trusted `setOnActions`, so a chat client could override a computed value (`role=guest` became `role=admin`) and persist it with `longTerm`. Such an entry is now honoured only when the engine wrote it (`Context.serverGenerated`) or the agent designer opted in with the new `acceptClientProperties` field of the property configuration (default `false`). A context entry without a type or value no longer throws.

Files: `PropertySetterTask`, `PropertySetter`, `IPropertySetter`, `PropertySetterConfiguration`; docs in `agent-config-authoring.md` and `security.md`.

### Behaviour change for upgraders

An integration that sent `properties*` expression context to set properties must set `"acceptClientProperties": true` in the property configuration. A client-sent entry is otherwise ignored (DEBUG log once per turn).
