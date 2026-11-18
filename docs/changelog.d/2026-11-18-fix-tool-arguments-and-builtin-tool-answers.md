## fix(tools): model-supplied httpcall arguments cannot change the JSON around them (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`)

### What changed and why

Review finding P2. On the LLM tool path a model-supplied string was substituted raw into the JSON
request body, so with body `{"name":"{name}"}` the argument `x","role":"admin` sent two fields with
the agent's credentials. A schema cannot forbid the quote (`pattern` and `format` are not enforced).

- New `ToolArgumentBodyEscaper` (`modules/apicalls/impl`). It rewrites the body **template**, because
  only the template knows where a placeholder sits: inside a JSON string literal the placeholder reads
  a JSON-escaped copy of the argument; as a bare value (`"count":{count}`) it reads the argument as
  canonical JSON (a number, boolean, object or array must be valid JSON of the declared
  `argumentSchema` type, otherwise the call is refused with a tool error; a string is emitted as a
  quoted JSON string; without a schema, valid JSON text keeps its type and anything else becomes a
  quoted string). A model-supplied value containing a control character is refused when a header
  template reads it.
- `HttpCallToolsProvider.templateDataFor` (shared by the gate-time fingerprint and execution) calls it
  and hands the rewritten template to `ApiCallExecutor.buildRequest` through the template data.
- New call field `escapeToolArguments` (default `true`) restores raw substitution for an author who
  builds JSON from argument text on purpose. The deploy-time warning now names only expressions that
  are not declared tool arguments, plus every expression of an opted-out call.

Not changed: whole-body variables (`{requestBody}`), `{json:serialize(...)}`, URL path/query
encoding, and author-controlled data (`{properties.x}`, `{context.y}`, API responses).

### Upgrade note

Stored configs keep deploying. Behaviour changes only where a model argument contained JSON-significant
characters (quote, backslash, control character) or, in a bare position, was not valid JSON of its
declared type, which is exactly the hole. Set `"escapeToolArguments": false` on a call to keep raw
substitution.
