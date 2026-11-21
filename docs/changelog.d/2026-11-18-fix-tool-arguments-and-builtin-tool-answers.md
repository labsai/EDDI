## fix(tools): model-supplied httpcall arguments cannot change the JSON around them (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`)

### What changed and why

Review finding P2, and its independent review (M1-M3, S2-S4, S7, S10). On the LLM tool path a
model-supplied string was substituted raw into the JSON request body, so with body `{"name":"{name}"}`
the argument `x","role":"admin` sent two fields with the agent's credentials. A schema cannot forbid the
quote (`pattern` and `format` are not enforced). A first fix rewrote *references* to an argument in the
template; the review showed the value still arrived raw through every other route (`{data:name}`,
`{#let}`/`{#each}`/`{#for}` aliases, `forEach` items, map keys, a map's `toString()`, `{id ?: 0}` in a
bare position). The redesign makes the property hold by construction:

- **The data is escaped, not the references** (`ToolArgumentBodyEscaper`, `modules/apicalls/impl`). The
  body of a tool call is rendered against a copy of the template data in which every model argument is an
  escaped twin of itself: every string, every map key and the printed text of every map or list is
  JSON-string-escaped. Whatever expression reads the value, inside a string literal it stays content.
- **A bare position receives only what the escaper serialised.** A plain `{name}` outside a string is
  rewritten to the argument's canonical JSON: on a typed call the value exactly as the schema check
  validated it (type arrays, `anyOf`, `enum`-only all work: the validator decides, not a re-guess);
  without a schema, valid JSON text as that JSON and anything else as a quoted string. Non-finite
  numbers are refused; numbers keep their digits (the schema validator now parses decimals as
  `BigDecimal`, and typed arguments render in plain notation).
- **Structural check as the backstop.** Anything else that can carry an argument outside a string is
  judged by rendering the body a second time against inert stand-ins (same shapes and lengths, every
  string that could form JSON structure replaced by private-use characters) and requiring both renders
  to parse as JSON of the same structure. A body that is not JSON once the arguments are in is refused.
  Cost: one extra render plus two parses per tool call whose JSON body names an argument; nothing
  otherwise.
- **Headers** are judged on the rendered value: a control character refuses the call however the
  template read the value.
- **Plumbing.** `HttpCallToolsProvider.templateDataFor` builds a `Guard` once per call (shared by the
  approval-gate resolution and the execution, so the pinned fingerprint is the body sent) and passes it
  to new `IApiCallExecutor.execute/resolve` overloads; the rewritten template no longer travels inside
  the template data. `forEach` and batch items over an argument array are guarded like the array. A
  refusal raised while the approval gate resolves the call refuses it before a human is asked
  (`ToolLoopRunner`, one catch block). Refusals count in `eddi.httpcalls.tool.argument.refused`.
- **Deploy time** (`ApiCallsTask`): the "unescaped splice" warning skips declared arguments only where
  the escaper runs (the same JSON predicate, escaping not opted out), and a new warning names every
  construct that reads an argument outside a string other than as a plain `{name}`.
- New call field `escapeToolArguments` (default `true`); `false` restores raw substitution.

Proof: `ToolArgumentInjectionTest` drives the real `HttpCallToolsProvider` → `ApiCallExecutor` →
templating path for 47 template × value cases plus forEach, headers, gate fingerprint, opt-out,
non-JSON and rule-path cases.

### Upgrade note

Stored configs keep deploying (warnings only). On the tool path, with a JSON body that names an
argument: an untyped argument in a plain bare position that is not valid JSON is now a quoted string
(`abc` → `"abc"`, `1,2` → `"1,2"`); a map printed inside a string is its escaped JSON instead of Java's
`{k=v}`; `{json:serialize(name)}` is unchanged; a compound expression or alias that puts a value of more
than one JSON token outside a string, and a body that is not valid JSON after substitution, now refuse
the call instead of sending it; a header value with a control character is refused. String methods on an
argument inside a string operate on the escaped text. A value a `preRequest` property instruction copies
from an argument into a property is author data from then on and is not escaped. The rule-triggered path
and `escapeToolArguments:false` render byte for byte as before.

```decision-log
| 2026-11-18 | Tool-argument escaping escapes the data, rewrites only a plain bare `{name}` to canonical JSON, and backs both with a structural check against an inert render | The first fix rewrote references to an argument and missed every alias, namespace and nested route | Patching each bypass in the reference rewriter; refusing every argument inside a section tag (would break working `{#each}` bodies) |
```

## fix(httpcalls): tool-argument guard closes late reference expansion, hyphenated forEach paths and stale typed values (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`), CodeRabbit review 5966230979 of PR #1491.

### What changed and why

- **References written by an argument are refused** (`ToolArgumentBodyEscaper.referencesComeFromTheTemplate`).
  `${vars:…}` (and the other `${ns:…}` references) are resolved after the structural check, and a variable's
  value is not escaped when it expands: a model argument `${vars:greeting}` inside a string literal passed the
  check and then expanded to `Welcome "back"`, breaking the JSON. A reference in the sent body that the inert
  render does not also hold (counted with multiplicity) was written by an argument and refuses the call.
- **Header check after `${vars:…}` expansion** (`ApiCallExecutor.buildRequest`), so a variable an argument
  names cannot carry a line break into a header (CWE-113).
- **`forEach` provenance by the selector's grammar** (`Guard.forIteration`, `rootKey`): `order-items` is one
  key, as `PathNavigator` reads it; the identifier scan read `order` and left an `{item}`-only body raw.
- **A typed value is used only while its binding is unchanged** (`Guard.typedFor`): a retry whose saved
  response replaced the argument sent the stale validated value, and a binding a `scope: "secret"`
  instruction scrubbed got its plaintext back. `templateValueOf` is the one definition the provider renders
  typed arguments with.
- **An empty render is refused** on the guarded path: a model-chosen boolean could switch off the section
  holding the document and send the request without a body.
- **Branch-preserving inert stand-ins**: any value without a character that can form JSON structure (quote,
  backslash, brace, bracket, comma, colon, `$`, control characters) is kept in the inert render, so
  `{#if mode == 'express delivery'}` takes the same branch in both renders (`isStructureFree`, replaces
  `isSingleToken`). A space or a non-ASCII letter cannot join JSON tokens, so the guarantee is unchanged.
- **Retries and `forEach`**: a refusal on a retry after a sent attempt keeps that attempt's outcome and adds
  `retryNotSent` instead of claiming nothing was sent; a refused `forEach` item refuses the whole call before
  any item is dispatched.
- **Performance**: the guard caches its template analysis, and in a `forEach` the guarded twins of bindings
  shared by every item (the array itself) are built once per call, not per item (identity cache).
- **Not executed means not executed** (`RefusalAwareToolExecutor`, `ToolLoopRunner`): an httpcall tool that
  refuses its arguments (guard, schema, unparseable body) reports it explicitly, the call counts as not
  executed, and the allowed-call observer (sampled audit) no longer records it.
- **Deploy-time warning** (`ApiCallsTask`): a call with `actions` can run on the rule path without escaping, so
  its argument expressions are warned about again.
- Built-in tools: `calculateDateDifference` counts elapsed time between zoned values (`Instant`, not calendar
  days); the scraper's `<meta charset>` prescan skips HTML comments; an overflowing calculator literal is an
  error, not an infinity that divides to 0; a Wikipedia answer without a `query` result (an `error` envelope, warnings alone) is a failed search.
- Docs: `ApiCall` Javadoc of `argumentSchema`/`escapeToolArguments` (no raw-rendering promise without a
  schema; the switch also covers the header check), `docs/httpcalls.md`, `docs/langchain.md`,
  `docs/metrics.md` (new reasons `empty`, `reference`); dashboard target for
  `eddi.httpcalls.tool.argument.refused` on the "Tool calls refused by argument schema" panel;
  `ReservedPrincipalsTest` classifies the `attachment:` provenance label.

### Upgrade note

On the tool path only: an argument containing a `${name:…}` reference in a guarded JSON body is refused
(before, a `${vars:…}` was expanded); a guarded body that renders empty is refused; a typed argument
replaced by a saved response or a scrub renders its live value. A call with `actions` gets the unescaped-splice
warning for its argument expressions again. Rule path unchanged.

```decision-log
| 2026-11-18 | Argument-written `${…}` references are found by comparing the sent body with the inert render, not by escaping `$` | One check covers every emission route, `{json:…}` included, and keeps author-written references and auto-vaulted properties working | Escaping `$` as `$` in the escaped twin (misses the raw `json:` path) |
```
