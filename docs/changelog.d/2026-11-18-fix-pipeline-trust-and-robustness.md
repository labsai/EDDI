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
- `CharacterUtilities.isNumber` / `isStringInteger` accepted any Unicode digit (`1` fullwidth, Arabic-Indic), and `Value.equals/hashCode` compared through `Float.parseFloat`, which threw `NumberFormatException` for those and made `unknown(123456789)` match input `123456790`. Both now accept ASCII digits only, and `Value` compares by `BigDecimal` (the text itself for a number over 100 characters); `equals`/`hashCode` are consistent and never throw.

### Behaviour changes for upgraders

- A decimal leaf in `property(...)` (`3.14`) is now stored as the string `3.14`, not as a float.
- A Unicode-digit token (for example fullwidth `1`) is no longer read as an integer by the integer dictionary and the word splitter; it is an ordinary word.
- Numeric rules compare exactly: `unknown(1)` still matches `1`, `1.0` and `1,0`, but integers above 2^24 no longer collide.

Files: `PropertySetter`, `CharacterUtilities`, `Value`; docs in `agent-config-authoring.md`.

## 🐛 fix(nlp): the model sees what the user typed; a long token cannot fail a turn (2026-11-18)

**Repo:** EDDI (`fix/pipeline-trust-and-robustness`)

### What changed and why

- `InputParser.normalize` always trims and collapses runs of spaces, and `InputParserTask` wrote the result over the conversation output `input`, which is what the model's history, the conversation log and `{memory.current.input}` read: pasted code and tables lost their indentation, even for an agent with no normalizers. The overwrite now happens only when the parser has normalizers (or `preserveInput: false`); otherwise the output `input` keeps the user's text as typed. A turn flagged `secretInput` keeps the previous behaviour (the plaintext for the turn, the placeholder re-asserted by `Conversation` at the end), so the vaulting flow is unchanged. `input:normalized` is still written.
- One unbroken token over 32,768 characters, or about 200 tokens of 165 characters, produced an `expressions:parsed` string over `eddi.expressions.max-length`; the next rule that read it threw and failed the turn. The parser now reduces an over-long result to one bounded `unknown(...)` expression (and sets `expressions:inputTruncated`), and every reader of the stored string (`InputMatcher`, `RulesEvaluationTask`, `PropertySetterTask`, the append merge) treats `ExpressionLimitExceededException` as "no expressions" (`StoredExpressions.parseOrEmpty`).
- LLM-first fast path: a parser with no normalizers, dictionaries, corrections or semantic fallback, and no quick replies to match, stores one `unknown(<text>)` expression (notation characters removed, at most 1,000 characters) without tokenising. Wizard-created agents keep matching their catch-all `inputmatcher "*"`.
- `docs/security.md` said conversation memory keeps the input verbatim; that is now true for the output `input` as well, whenever the parser has no normalizers.

New parser config keys: `preserveInput` and `passThrough` (both default to automatic; see `docs/semantic-parser.md`).

### Behaviour changes for upgraders

- With no normalizers, `{memory.current.input}` and the model's history hold the typed text (leading/trailing whitespace and runs of spaces included) instead of the trimmed, collapsed text. Set `"preserveInput": false` on the parser step to get the old behaviour.
- A hand-built parser with no extensions now yields one `unknown(<whole text>)` for a multi-word input instead of one `unknown(word)` per word; a rule such as `inputmatcher unknown(hello)` then matches only the exact one-word input. Set `"passThrough": false` to keep per-word expressions.

Files: `InputParserTask`, `IInputParser`, `InputParser`, `IExpressionProvider`, `ExpressionProvider`, `StoredExpressions`, `InputMatcher`, `RulesEvaluationTask`, `PropertySetterTask`; docs in `semantic-parser.md` and `security.md`.

