## ✨ feat(parser): correct phrase search, match confidence, partial phrases and an embedding fallback (2026-11-06)

**Repo:** EDDI (`feat/inputparser-improvements`)

### What changed and why

A critical re-read of the input parser (`modules/nlp`) found defects that made it silently miss inputs its
dictionaries covered, plus measurement it computed and threw away. Each defect below was reproduced
before it was fixed, and each fix was mutation-checked (re-introducing the defect fails its test).

**Defects fixed**

- **The combination search skipped readings.** `IterationCounter` capped every token's match index by the
  *last* token's match count, so a token with more readings than the last one never had its alternatives
  tried (`[1,0]` produced 1 of 2 combinations, `[2,0,0]` 1 of 3) while out-of-range plans were generated
  and discarded. A typo in the first word of a phrase, whose phrase reading was a correction's second
  candidate, therefore never matched the phrase. Replaced by a complete best-first enumerator (increasing
  total rank, then lexicographic), linear per step, allocation-free; `Permutation` is gone.
- **A complete phrase match found after the first combination was appended behind worse solutions**, and
  the pipeline takes the first one. It now goes to the front.
- **Partial phrase matching never worked**: `lookForPartlyMatch` compared phrase words with *found* words
  (different classes, never equal) and mixed up the two lists' indexes. Rewritten, and opt-in — see below.
- **Quick replies were not normalized** like the input they are compared with: under the punctuation
  normalizer a click on `Yes, please!` arrived as `Yes , please !` and matched nothing.
- **Generated quick-reply expressions were ASCII-only** (`[^a-zA-Z0-9 ]`): `Größe` became `gre`, Cyrillic,
  Arabic or CJK quick replies an empty expression. Letters and digits of every script are kept now.
- **Phrases of a dictionary in another language were candidates** — phrases are matched by word value, so
  a `de` phrase could match in an `en` conversation once the English dictionaries knew its words.
- **A phrase assembled from typo corrections reported accuracy 1.0**; it is now as accurate as its least
  accurate word and counts as corrected.
- **The `/parser` endpoint skipped the normalizers**, so it answered for an input the pipeline never parses.
- `won't` normalized to "would not" (now "will not"); typographic apostrophes (`don’t`, as phones type it)
  were never expanded; 20 common contractions added.
- The email dictionary rejected top-level domains longer than four letters (`.technology`, `.online`).
- `toLowerCase()` without a locale in the nlp module (Turkish-locale JVMs).

**Performance** (none measured as a regression, all remove work per input token):
`RegularDictionary.lookupTerm` returned one match per phrase containing a word plus a 0.9 copy of every
exact hit (now one per distinct word, from an index built once) and checked case on a parallel stream over
all keys; `Phrase.getWords()` re-split and re-allocated on every call (now cached); duplicate matches of
one token are dropped before enumeration; `RawSolution` has content equality, so the solution list no
longer fills with copies; without phrases, `parse` stops at the chosen reading plus `maxAlternatives`
(`parseAllReadings` keeps the full list for the `/parser` endpoint); the Damerau–Levenshtein correction
applies its length bound before lower-casing each dictionary word.

**New, all opt-in or diagnostic**

- `expressions:confidence` (accuracy of the least accurate contributing match), `expressions:inputTruncated`
  (input over `maxInputTokens`), and accuracy/corrected markers in `expressions:matches`.
- `minMatchingAccuracy` (default 0): matches below it contribute no expressions — "do not act on a guess".
- `maxAlternatives` (default 0, max 10): other readings as `expressions:alternatives`.
- `matchPartialPhrases` (default off) + `minPartialPhraseCoverage` (0.5): a fragment yields its phrase with
  accuracy = coverage; equally covered ambiguous fragments yield nothing.
- `semanticFallback`: when the dictionaries understood nothing, the input is compared by embedding
  similarity with example sentences (explicit, and by default the dictionaries' own phrases) and the best
  example at or above `minScore` (0.8) supplies its expressions. Reuses `EmbeddingModelFactory` (vault
  secrets, host allow-list, SSRF policy, cache). Examples are embedded once on first use; a provider failure
  is "no match" with a 60 s back-off; a malformed block fails the deployment. Metric
  `eddi.parser.semantic.fallback{outcome}`.
- **Vault grant enforcement now covers parser configurations**: `VaultGrantChecker` reads parser documents
  and scans every step's inline `config`/`extensions`, so a `${vault:…}` key in a parser's
  `semanticFallback` — stored or inline — is checked against the agent's grant like an LLM's.
- From review: a quick-reply phrase with the same text as a configured phrase now yields the quick
  reply's expressions (single quick-reply words already won); `UnrestrictedSecretsCheck` scans each
  string value with the linear reference scanner instead of one regex over the serialized document,
  where an unterminated `${vault:` in one field hid the reference in the next; the new counter is
  documented in [`metrics.md`](../metrics.md) and charted on the full-metrics dashboard ("Input Parser" row).
- From the second review (after merging main): a variable-store failure during the grant check's in-place
  `${vars:…}` expansion escaped to the catch-all that answers "no violations" — it now fails closed through
  the per-variable path; the semantic fallback reads the same token-limited input and the same effective
  language (default `en`) as the dictionaries, and an example with a language never matches a turn without
  one; `minMatchingAccuracy` takes part in choosing a token's reading (qualifying readings first, and
  readings of different accuracy are no longer collapsed); a complete phrase reading no longer cuts off the
  readings asked for by `maxAlternatives` / `parseAllReadings`; an ambiguous phrase fragment no longer
  suppresses unambiguous fragments elsewhere; the phrase index uses the same case-insensitive equivalence as
  the word map (Turkish dotted/dotless i), and the Damerau–Levenshtein length pre-check allows for lower-case
  expansion (`İ`).
- From the third review: a phrase or phrase fragment whose final accuracy is below `minMatchingAccuracy` is no
  longer taken (it outranked a qualifying word reading and then yielded nothing); the semantic fallback
  re-embeds its examples when the factory hands out a new model (changed variable or secret), so input and
  examples share one embedding space; an unsupported `embeddingProvider` fails the deployment; a parser that
  replaces an earlier result on the same step (`appendExpressions: false`) clears its confidence,
  alternatives and matches.

**Files:** [`InputParser.java`](../../src/main/java/ai/labs/eddi/modules/nlp/internal/InputParser.java),
[`IterationCounter.java`](../../src/main/java/ai/labs/eddi/modules/nlp/internal/matches/IterationCounter.java),
[`InputParserTask.java`](../../src/main/java/ai/labs/eddi/modules/nlp/InputParserTask.java),
[`SemanticFallback.java`](../../src/main/java/ai/labs/eddi/modules/nlp/semantic/SemanticFallback.java),
[`RegularDictionary.java`](../../src/main/java/ai/labs/eddi/modules/nlp/extensions/dictionaries/RegularDictionary.java),
[`VaultGrantChecker.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java),
[`semantic-parser.md`](../semantic-parser.md); Platform Operator prompt (revision 63).

### Behaviour changes for existing agents

Agents that relied on a defect parse differently: inputs whose phrase needed a non-first reading of an
early token now match the phrase; a complete phrase match now wins over an earlier partial reading;
quick replies with punctuation now match under the punctuation normalizer; `won't` expands to "will
not". The pinned enumeration order in `IterationCounterTest` was an artifact of the old scheme and was
replaced by the documented best-first order.

### What's next

- The Manager's parser editor has no form fields for the new keys yet (they work in the JSON tab).
- The `/parser` endpoint takes no language and does not consult the semantic fallback.

```decision-log
| 2026-11-06 | Parser semantic fallback runs only when the dictionaries understood nothing, and yields an example's expressions | Paraphrases missed by dictionaries | Classifying every input by embeddings (cost per turn, rules lose determinism); free-text LLM intent output |
| 2026-11-06 | Partial phrase matching is opt-in, ambiguous fragments yield nothing | The dead implementation was fixed | On by default (fewer words than designed would trigger a phrase); picking the first ambiguous phrase |
```
