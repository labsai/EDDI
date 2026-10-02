## 🐛 fix(pipeline): typed postResponse properties, one property-instruction engine (2026-10-02)

**Repo:** EDDI (`fix/pipeline-properties-parser`) — review 2026-10-02 §2 #5, §4.4

### What changed and why

- **`postResponse` property instructions stored `""` for every non-string.** `PrePostUtils` replaced any number, boolean, object or list read through `fromObjectPath` with an empty string (live: a GitHub repository's `stargazers_count` and `private` came back empty). It also ignored `override`, `toObjectPath` and the typed `value*` fields, and overwrote a property with `""` when the path resolved to nothing. Its tests asserted only `any(Property.class)`.
- **`PropertySetterTask` silently dropped `Double` and `Long`** — the types Jackson gives a JSON decimal and a whole number beyond the `int` range — and an inline `property.json` `valueFloat: 1.5` (a `Double` after Jackson) was dropped at configure time.
- **One engine.** Both now run every instruction through the new [`PropertyInstructionExecutor`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertyInstructionExecutor.java), so `property.json` and httpcall / MCP / LLM `preRequest` / `postResponse` instructions behave identically (typed values, `override`, `toObjectPath`, `convertToObject`, typed fields, scrub-placeholder guard, `scope: secret`). The mapping of a value to a property slot lives in [`PropertyValues`](../../src/main/java/ai/labs/eddi/configs/properties/model/PropertyValues.java), also used by `ConversationProperties`, `UserMemoryEntry`, `Conversation` (a `longTerm` `Double` reloaded from the user-memory store used to come back as a string) and `MemoryCheckpoint`.
- **`Property` gains `valueLong` and `valueDouble`** (additive JSON fields; existing documents are unchanged). A `Long` that fits an `int` is stored as `valueInt`, so the type survives a MongoDB/PostgreSQL round trip; a `Double`/`BigDecimal` keeps full precision instead of being rounded to a `Float`.
- **Failures are visible.** A failing `preRequest`/`postResponse` instruction used to be one `LOGGER.error` line. It is now logged with the conversation id and recorded in the step under `propertyInstructions:errors`; the remaining instructions still run. `scope: secret` still fails closed.
- **Behaviour change:** a `postResponse` `fromObjectPath` that resolves to nothing now sets nothing (it used to store `""`), the same as `property.json`.
- `PrePostUtils.verifyHttpCode` no longer writes default lists into the shared, cached `HttpCodeValidator`.

**Docs:** [`properties.md`](../properties.md) (wrong `httpCalls.` root in both `fromObjectPath` examples, "stored exactly as found" is now true, visibility table contradicted the prose — the default is `global` without a `userMemoryConfig`), [`httpcalls.md`](../httpcalls.md) (model table and sample brought up to date, `actions` is a list, `{userInfo.userId}`, nonexistent `stemming` correction removed, hard-coded OpenWeatherMap key replaced by a `${vault:…}` reference, broken anchors, "package"), [`architecture.md`](../architecture.md) and [`conversation-memory.md`](../conversation-memory.md) examples (the response object they read was never saved).

```decision-log
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
```

## 🔒 fix(nlp): parser steps honour parserstore documents; client quick replies mint no expressions (2026-10-02)

**Repo:** EDDI (`fix/pipeline-properties-parser`) — review 2026-10-02 §4.4 N1, N3

### What changed and why

- **N1 — a parser step referencing a `parserstore` document ran with no dictionaries.** `InputParserTask.configure` never read the step's `config.uri`, which is exactly the shape `AgentSetupService`, the MCP setup tools and the Manager's pipeline builder write. It now loads the document; the step's `config` keys override the document's, and the step's `extensions` are appended after the document's per extension point. An unreadable `uri` fails the deployment instead of running empty. The extension descriptor lists the optional `uri`.
- **N3 — a client could mint any expression.** `OutputGenerationTask` copied client-supplied `context.quickReplies` (and `quickReply` items in a client `context.output`) into the step like the agent's own, and the next turn's parser turned them into a temporary dictionary — so a client offering itself `{"value":"Approve","expressions":"approve_refund"}` got `approve_refund` parsed, and on every setup- or MCP-created agent (which forced `expressionsAsActions: true`) that became an **action**. The parser now builds the temporary dictionary from the previous step's `quickReplies:*` data, which only the agent's own quick replies get: client ones are displayed (without expressions) but never stored as data. Quick replies a `postResponse` builds are marked server-generated on their `Context` (a `@JsonIgnore` flag a client cannot set) and keep working.
- `AgentSetupService.createBehaviorConfig` no longer forces `expressionsAsActions: true`; its single rule (`inputmatcher *`) does not need it. Existing setup-created agents keep the flag in their stored rule set; N3 is closed for them by the parser change.
- The parser read the quick-reply flag under `"default"` (never written; `QuickReply` serializes `isDefault`), a quick reply without `value` threw an NPE, and `OutputGenerationTask.convertQuickRepliesConfig` dropped `isDefault`. All fixed. `OutputGenerationTask.configure` sorted the cached output set in place; it now sorts a copy.

**Docs:** [`semantic-parser.md`](../semantic-parser.md), [`agent-config-authoring.md`](../agent-config-authoring.md), [`output-configuration.md`](../output-configuration.md), [`behavior-rules.md`](../behavior-rules.md) (`appendActions`, `expressionsAsActions`, the special `empty` matcher value), [`putting-it-all-together.md`](../putting-it-all-together.md) (the booking flow read `{context.*}` where the values live in properties, hard-coded `contextmatcher` values, a stray `$`, a wrong parser result, "packages"), [`extensions.md`](../extensions.md), [`output-templating.md`](../output-templating.md) (`_count` is the 1-based index in Qute), and the rule-based reference fixture (a quick reply used the reserved `CONVERSATION_END` as an expression; a rule now ends the conversation).

```decision-log
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
```
