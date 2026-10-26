## 🐛 fix(llm): a `\'` inside a JSON reply no longer turns a complete answer into the fallback (2026-10-26)

**Repo:** EDDI (`fix/llm-json-repair-backslash-quote`)

### What changed and why

Gemini sometimes escapes an apostrophe inside a JSON string the way JavaScript and Python allow: `"Japan\'s ruling class"`. JSON has no `\'` escape, so `ModelOutputParser` reported `invalid JSON syntax` for the whole reply, and a `convertToObject` task served its fallback answer ("Sorry, I could not answer that just now"). In the case that showed it, the reply was complete and correct, about 1,300 characters long, and one apostrophe was the only defect.

[`ModelOutputParser`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java) now drops the backslash of `\'` inside JSON strings, after the fence strip and before parsing. This is the one exception to its "invalid JSON is never fixed" rule, and the class Javadoc says so. It is safe because `\'` is never valid JSON and can only mean `'`.

- The pass is string-aware and linear. It copies every other escape as the pair it is, so `\\'` (an escaped backslash, then a quote) stays valid JSON and `\"` does not end the string. Outside strings nothing changes.
- A reply without `\'` is not copied.
- A repaired reply reports `repaired`, like a stripped fence.
- A reply that is still invalid keeps its raw text, and a second, real syntax error is not hidden.

### Tests

`ModelOutputParserTest`:
- accepted: the observed case, several `\'` in one object and inside an array, fenced, and with prose around it;
- left alone: `\\'` stays valid and unrepaired, and an escaped double quote does not end the string;
- still rejected: prose containing `\'` is `not JSON` with the raw text kept, and `\'` plus a trailing comma is still `invalid JSON syntax`.

Mutation check: with the repair switched off, exactly the five acceptance cases that contain `\'` fail; the `\\'` case passes either way, as it should.
