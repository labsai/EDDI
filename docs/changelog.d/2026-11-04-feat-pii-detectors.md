## Shared PII detectors; `pii-redaction` finds IBANs and German identifiers (2026-11-04)

**Repo:** EDDI · **Branch:** `feat/pii-detectors`

The first piece of the reversible pseudonymization work: a detector layer that the `pii-redaction` guardrail and a later pseudonymization block share, so both find exactly the same values.

### What changed

- **New package `modules/llm/pii/`.**
  - `PiiDetector`: an SPI of CDI beans selected by `type()`, like `ContentGuardrail`. It comes with `PiiSpan`, `DetectorContext` and `DetectorUnavailableException`. A detector that cannot finish throws the exception; it never reports "nothing found" for a text it did not check.
  - `BuiltinPiiDetector` (`builtin`) and `BuiltinRecognizers`: one pattern-plus-validation recognizer per entity type. The types are `EMAIL`, `US_SSN`, `CREDIT_CARD` and `PHONE` (the patterns moved here unchanged), plus `IBAN`, `DE_TAX_ID`, `DE_SOCIAL_SECURITY`, `DE_ID_CARD` and `DATE_OF_BIRTH`.
  - `PiiChecksums`: Luhn, ISO 13616 mod-97 with per-country lengths, the Steuer-ID MOD 11,10 check and repetition rule, the ICAO 9303 check digit, and the Rentenversicherungsnummer check digit.
  - `PiiSpanMerger` and `TermMatcher`: overlap resolution, in priority order the author's always-terms, then the longer span, the higher score and the earlier detector. Spans inside never-terms and inside skip ranges (placeholders EDDI wrote) are dropped. Term matching is whole-word and case-insensitive.
- **`PiiRedactionGuardrail`** is rebuilt on `BuiltinRecognizers` and gains the five new kinds without a configuration change. With `patterns` absent, all nine run.
  - The kinds run one after another, structured identifiers first, so the phone pattern no longer takes an IBAN's digit groups (before, `DE89 3704 0044 0532…` came out as a partial `[REDACTED:phone]`).
  - The four original kinds keep their names, their `{kind}` value and their reason, and their output is byte-identical. `PiiRedactionGuardrailTest` checks this against `LegacyPiiRedaction`, a frozen copy of the old rewrite, over a fixed corpus and 3,000 random fragment combinations.
  - An unknown kind in `patterns` is now a deploy warning (it used to select nothing, silently).
- **Manager:** the PII kind checkboxes list all nine kinds, with labels in all 11 locales.
- **Operator:** the system prompt lists the new kinds; the operator revision is bumped.
- **Docs:** [`langchain.md`](../langchain.md) (the `pii-redaction` row) and [`security.md`](../security.md).

### Design decisions

- **The guardrail applies kinds sequentially** rather than through the merger. Its replacement text is author-configurable and the legacy result depends on later kinds seeing earlier replacements, so only sequential application keeps the output byte-identical. The merger is for pseudonymization, which replaces each value with its own token.
- **`DE_ID_CARD` and `DATE_OF_BIRTH` need a keyword** within 40 characters before the value: a nine-character serial without its check digit, and a bare date, match far too much on their own. `DE_TAX_ID` needs no keyword; the check digit and repetition rule let roughly 1 in 300 random 11-digit numbers through, and none with a leading zero.
- **No new regex budget class, and no budget on the guardrail.** `RegexDeadline` already provides one, and `BuiltinPiiDetector` honours the caller's deadline (a pseudonymization chain will need it). The guardrail does not: its patterns are EDDI's and bounded, so a scan is linear, and a 250 ms budget made the existing 200,000-character linear-scan test undecided. `regex-filter` keeps its budget because its patterns are the author's.
- **The package is `modules/llm/pii`,** not `modules/llm/pseudonymization` as the plan said: the detectors serve both features.

### Next

PR 2 of the pseudonymization plan: the token codec, key provider, session and restorer, unit-tested and not yet wired into `LlmTask`.
