## 🔒 fix(properties): a client can no longer set conversation properties through context (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

A context entry whose key starts with `properties` and whose type is `expressions` was parsed by the property step of every agent, after the trusted `setOnActions`, so a chat client could override a computed value (`role=guest` became `role=admin`) and persist it with `longTerm`. Such an entry is now honoured only when the engine wrote it (`Context.serverGenerated`) or the agent designer opted in with the new `acceptClientProperties` field of the property configuration (default `false`). A context entry without a type or value no longer throws.

Files: `PropertySetterTask`, `PropertySetter`, `IPropertySetter`, `PropertySetterConfiguration`; docs in `agent-config-authoring.md` and `security.md`.

### Behaviour change for upgraders

An integration that sent `properties*` expression context to set properties must set `"acceptClientProperties": true` in the property configuration. A client-sent entry is otherwise ignored (DEBUG log once per turn).

## 🐛 fix(properties): `property(...)` values are kept verbatim; numbers cannot fail a turn (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- `property(host(api.openai.com))` stored `openai.com` and `1.2.3` became `2.3`, because the leaf went through `Expression.setExpressionName`, which splits at the first dot. `4915112345678` threw in `Integer.parseInt` and failed the turn, `01234` became `1234`. `PropertySetter` now keeps the leaf as a string, except a plain integer (ASCII digits, no leading zero), which becomes an `Integer`, or a `Long` beyond the int range, and `true`/`false`, which become a boolean. Nothing in it can throw.
- `CharacterUtilities.isNumber` / `isStringInteger` accepted any Unicode digit (`1` fullwidth, Arabic-Indic), and `Value.equals/hashCode` compared through `Float.parseFloat`, which threw `NumberFormatException` for those and made `unknown(123456789)` match input `123456790`. Digits of other scripts are still numbers: the integer and decimal dictionaries convert them to ASCII when they build the expression (`integer(12)` for `١٢`), and `Value` keeps its text as written but converts it where it compares (`١٢` equals `12`, with the same hash). `Value` compares by `BigDecimal` (the text itself for a number over 100 characters); `equals`/`hashCode` are consistent and never throw.

### Behaviour changes for upgraders

- A decimal leaf in `property(...)` (`3.14`) is now stored as the string `3.14`, not as a float (a float would corrupt `1.10`). A Qute comparison such as `{#if properties.price > 1}` or a body that expects a number changes with it: keep the number in a `valueDouble` instruction when a rule or template has to compare it.
- Numeric rules compare exactly: `unknown(1)` still matches `1`, `1.0` and `1,0`, but integers above 2^24 no longer collide.

Files: `PropertySetter`, `CharacterUtilities`, `Value`; docs in `agent-config-authoring.md`.

## 🐛 fix(nlp): the model sees what the user typed; a long token cannot fail a turn (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- `InputParser.normalize` always trims and collapses runs of spaces, and `InputParserTask` wrote the result over the conversation output `input`, which is what the model's history, the conversation log and `{memory.current.input}` read: pasted code and tables lost their indentation, even for an agent with no normalizers. The overwrite now happens only when the parser has normalizers (or `preserveInput: false`); otherwise the output `input` keeps the interior of the user's text exactly as typed and only loses leading and trailing whitespace, as it always did (so `{memory.current.input}` in a slot-filling property or an HTTP body, and a `dynamicvaluematcher == 'yes'`, do not see a trailing newline). A turn flagged `secretInput` keeps the previous behaviour (the plaintext for the turn, the placeholder re-asserted by `Conversation` at the end), so the vaulting flow is unchanged. `input:normalized` is still written.
- One unbroken token over 32,768 characters, or about 200 tokens of 165 characters, produced an `expressions:parsed` string over `eddi.expressions.max-length`; the next rule that read it threw and failed the turn. The parser now reduces an over-long result to one bounded `unknown(...)` expression (and sets `expressions:inputTruncated`), and every reader of the stored string (`InputMatcher`, `RulesEvaluationTask`, `PropertySetterTask`, the append merge) reads an over-limit stored string as that one bounded `unknown(...)` expression, not as an empty list, so an empty-input rule does not match (`StoredExpressions.parseOrEmpty`). The `unknown(...)` is sized to the provider's limit, an integer or decimal token over the limit is read as an unknown word, and the diagnostics (`expressions:matches`, `:confidence`, `:alternatives`) are published only for a reading that is kept.
- LLM-first fast path, opt-in with `passThrough: true` (the setup wizard writes it): a parser with no normalizers, dictionaries, corrections or semantic fallback, and no quick replies to match, stores one `unknown(<text>)` expression (notation characters removed, at most 1,000 characters, not cut inside a surrogate pair) without tokenising. Wizard-created agents keep matching their catch-all `inputmatcher "*"`.
- `docs/security.md` said conversation memory keeps the input verbatim; it now says exactly what is kept: `input:initial` always, and the output `input` minus leading and trailing whitespace whenever the parser has no normalizers.

New parser config keys: `preserveInput` (automatic by default) and `passThrough` (default off; see `docs/semantic-parser.md`).

### Behaviour changes for upgraders

- With no normalizers, `{memory.current.input}` and the model's history hold the text with its interior as typed (line breaks, runs of spaces) instead of the collapsed text; the ends are still trimmed. Set `"preserveInput": false` on the parser step to get the old behaviour. A turn flagged `secretInput` is unchanged.
- Nothing changes for existing parser configs regarding per-word `unknown(...)`: `passThrough` is off unless set.
- Only an over-long parser result is reduced, to one bounded `unknown(...)`; it was a failed turn before.

Files: `InputParserTask`, `IInputParser`, `InputParser`, `IExpressionProvider`, `ExpressionProvider`, `StoredExpressions`, `InputMatcher`, `RulesEvaluationTask`, `PropertySetterTask`; docs in `semantic-parser.md` and `security.md`.

## 🐛 fix(rules,output): save-time validation of rule graphs and `timesOccurred`; no endless loop in output selection (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- A ruleset with a `dependency` cycle saved, deployed READY and then made `POST /agents/{id}/start` answer an empty HTTP 500. `RuleSetSaveValidator` (called from `RuleSetStore.validate`) now refuses on save a dependency cycle (rules are told apart by name, as the loop guard does), a `dependency` on a name no rule has, and two rules of one group with the same name. Unnamed rules cannot be referenced, so they only matter as a dangling target. Stored rulesets still load and deploy: the checks run on save only.
- A failing `CONVERSATION_START` turn answers `422 agent_start_failed` and creates no conversation (see below); a rule-set failure on a later turn is a task error of that turn.
- `OutputGeneration.getOutputs` looped forever when every entry of an action had `timesOccurred` below -1 (or the entry list was empty). One extraction now decides: nothing selectable gives an empty result. `OutputStore` refuses `timesOccurred < -1` on an interactive create and update.
- An output set whose `lang` does not match the conversation's still produces nothing (matching is unchanged), but the task now logs a WARN once per deployed configuration (`IOutputGeneration.claimLanguageMismatchLog`).

### Behaviour changes for upgraders

- Saving a ruleset with a dependency cycle, a dangling dependency or duplicate rule names in a group is refused (HTTP 400, rule named). Only conditions the runtime keeps count (the children of a `connector` or `negation`); every independent cycle is reported. Import, sync, upgrade and duplicate only warn (see below).
- An output set with `timesOccurred` below -1 is refused on an interactive save; a lenient write keeps the entry, which stays unselectable.

## 🔒 fix(templating): data cannot inject expression notation into quick replies; smaller pipeline items (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- The `expressions` field of a quick reply is templated with property or API data and becomes a trusted dictionary phrase on the next turn. Data such as `x), intent(admin` therefore produced the user-typeable expression `intent(admin)`. The field is now rendered by a notation-safe engine that strips expression notation from every value it substitutes (see the review follow-up below for what exactly is stripped), in `OutputTemplateTask` (output-set quick replies), `PrePostUtils` (quick replies built by a `postResponse`) and `LlmFallbackHandler`. The quick reply's value keeps the data, so what the user sees and types is unchanged.
- `LanguageUtilities.isTimeExpression` turned `12:24:00` into midnight because it tested `contains("24:00")`; it now tests the whole value.
- `STOP_CONVERSATION` ends the turn right after the task that emitted it, so a farewell output for that action never runs. Semantics unchanged; `agent-config-authoring.md` now says to use `CONVERSATION_END` for a farewell.

### Left as it was

`behavior_rules:fail` is still written every turn: `docs/developer-quickstart.md` presents it as a debugging aid, so a reader exists.



## 🐛 fix(start,rules,templating): review follow-up (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- **`POST /agents/{id}/start`:** the `catch` in `Conversation.init` that kept the conversation in state `ERROR` is gone; it removed the documented `422 agent_misconfigured` and persisted a conversation for every start of a broken agent. Instead a start turn whose rule evaluation fails (`RuleEvaluationFailedException`, marked `AgentStartFailure`) answers `422 {"error":"agent_start_failed","message":<fixed text>,"retryable":false}` and creates no conversation. A vault outage, a database error and an interrupt stay a 500, as documented; the marker is deliberately not "every task failure". See `docs/rest-api-reference.md`.
- **Lenient writes:** the rule-graph checks and the `timesOccurred` check refuse an interactive save (REST, MCP, the Manager) only. Import, sync, upgrade and duplicate run inside `LenientConfigWrite` and log one WARN per problem instead, so an archive that imported before still imports.
- **Quick-reply expressions:** a quick reply's `expressions` is rendered by `ITemplatingEngine.processExpressionsTemplate`, an engine with a result mapper that strips `(`, `)` and `,` from every value an expression emits (and drops a value that is only `*`, which equals every expression). Conditions, loops and filters see the original data, the template's literal text is kept, and label and expression of one quick reply come from the same row. The same renderer serves output templates, `postResponse` quick replies and the LLM fallback quick replies.
- **`property(...)` leaves:** digits of other scripts stay the text as written (`property(customerCode(١٢))` stores `١٢`); they are converted only where a number is compared or converted. A 19-digit integer is a number when it fits a `long`.
- **Parser:** `passThrough` is opt-in (the setup wizard writes it); a stored over-long `expressions:parsed` reads as one bounded `unknown(...)` and no longer as "the user said nothing"; with `appendExpressions` the earlier results are kept when a new result would pass the limit; the bounded text is never cut inside a surrogate pair.

### Behaviour changes for upgraders

- An agent whose start turn fails because of its rule set now gets the typed 422 instead of a bare 500.
- Digits of other scripts are numbers again for the dictionaries and for rule comparison (reverts the first draft of the number fix, which dropped `integer(...)` for them); only a `property(...)` leaf keeps them as the text written.

## 🔒 fix(templating,nlp,rules): second review follow-up — the wildcard, over-limit tokens, one selection (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- **Wildcard through Unicode whitespace.** The notation-safe engine dropped a value that was only `*` by `String.trim()`, which removes characters up to U+0020 only, while the expression parser skips every `Character.isWhitespace` character: data `"　*"` in `choose({properties.item})` read as `choose(*)` on the next turn, which matches every `choose(x)` rule. Stripping now asks the parser (`ExpressionProvider.isNotationCharacter`, `ExpressionProvider.readsAsWildcard`) instead of keeping its own rules, so it agrees with the parser by construction; a test runs all 65,536 UTF-16 units through strip-then-parse. The parser itself had two notions of whitespace — `String.trim()` for a name in front of `(`, `isWhitespace` everywhere else, so `"\u0001*(a)"` also read as the wildcard — and now uses one: `isWhitespace` plus every character up to U+0020. No-break spaces and zero-width characters stay part of a name, so `" *"` is no wildcard, as before.
- **Over-limit tokens.** An e-mail token longer than `eddi.expressions.max-length` made `EmailDictionary` throw `ExpressionLimitExceededException` out of the parser and failed the turn (only the integer and decimal dictionaries caught it). `IDictionary.lookupWithinLimits` now catches it for every dictionary lookup with user text — the parser's direct lookup and `MergedTermsCorrection` — so a dictionary added later is covered too; the token is read by the other dictionaries or as unknown.
- **One selection for `postResponse` quick replies.** Value and expressions are rendered by two engines, so in two passes; the second pass evaluated the `templateFilterExpression` again, and a filter that is not deterministic (`uuidUtils:`, a clock) could select other rows with the same count, giving a value another row's expressions. The first pass now records the filter's verdict per element and the second replays it (`PrePostUtils.IterationSelection`). If the array comes out with another length the second time (a getter that is not idempotent), every quick reply keeps its value and gets no expressions — the next turn still recognises the value, as a temporary dictionary entry without expressions — and a WARN is logged.
- **`RuleSetSaveValidator`:** the reported cycle is found in linear time (it searched an `ArrayList` per step, quadratic on a 100,000-rule cycle) and starts at the rule that comes first in the ruleset.
- **Docs:** what the notation-safe engine strips is written down in `docs/security.md` (Data in expression notation) and `docs/agent-config-authoring.md`.

### Behaviour changes for upgraders

- In a quick reply's `expressions` field, every value an expression emits is stripped of `(`, `)` and `,` — also a literal an operator returns (`{x ?: 'intent(help)'}`, `{x.or('a,b')}`, a ternary, `{#let v='a,b'}{v}`, `str:concat`/`str:fmt`). Text written in the template, including text inside `{#if}` sections, is kept: write conditional notation as section text. A list renders as `[a b]`, data `1,500` as `1500`, and a value with a comma or parentheses no longer equals a rule written with the raw value. A value that reads as `*` (with any whitespace the parser skips) becomes empty.
- A token over the expression length limit is an unknown word for every dictionary, not only for numbers.

### Left open

- `Unknown` (a token no dictionary knew) still trims with `String.trim()`; a user can already type `*` and get `unknown(*)`, so a padded star adds nothing new there.
- The two `postResponse` passes each get the full render deadline and caps; a failure in either fails the whole quick-reply build, so no half-paired result can be stored.
