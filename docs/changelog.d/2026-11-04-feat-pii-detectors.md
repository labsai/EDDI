## Shared PII detectors; `pii-redaction` finds national identifiers of 27 countries (2026-11-04)

**Repo:** EDDI · **Branch:** `feat/pii-detectors`

The first piece of the reversible pseudonymization work: a detector layer that the `pii-redaction` guardrail and a later pseudonymization block share, so both find exactly the same values. It recognizes identifiers from around the world, not only German ones.

### What changed

- **New package `modules/llm/pii/`.**
  - `PiiDetector`: an SPI of CDI beans selected by `type()`, like `ContentGuardrail`. It comes with `PiiSpan`, `DetectorContext` and `DetectorUnavailableException`. A detector that cannot finish throws the exception; it never reports "nothing found" for a text it did not check.
  - `BuiltinPiiDetector` (`builtin`) and `BuiltinRecognizers`: one pattern-plus-validation recognizer for each of 40 entity types.
    - Universal types: `EMAIL`, `PHONE`, `CREDIT_CARD`, `IBAN`, `IP_ADDRESS`, `PASSPORT_MRZ` (ICAO 9303 TD3) and `DATE_OF_BIRTH`. Birth keywords are recognized in eleven languages.
    - National identifiers of 27 countries, each named `<ISO code>_<id>`: US, CA, BR and MX; GB, IE, FR, IT, ES, PT, NL, BE, DE, AT, CH, PL, SE, DK and FI; IN, CN, JP, KR, SG, AU and NZ; and ZA.
    - The patterns of the original four types moved here unchanged.
  - `PiiCountries` holds the standard set (`EMAIL`, `US_SSN`, `CREDIT_CARD`, `PHONE`, `IBAN`) and the country→types map.
  - Check digits:
    - `PiiChecksums`: Luhn, ISO 13616, and the German identifiers.
    - `NationalChecksums`: the rest. These include mod-97 (NIR, Belgium), mod-23 letters (DNI/NIE, PPS), mod-11 variants (NHS, BSN, NIF, PESEL, SVNR, CPF, TFN, IRD, My Number, CN ID), Verhoeff (Aadhaar), EAN-13 (AHV), mod-31 (HETU), the codice fiscale letter, CURP, NRIC/FIN and the ICAO MRZ digits.
    - Each validator is tested against published valid examples, not against values generated with the code under test.
  - `PiiSpanMerger` and `TermMatcher`: overlap resolution. The priority order is: the author's always-terms, then the longer span, then the higher score, then the earlier detector. Spans inside never-terms or inside skip ranges (placeholders EDDI wrote) are dropped. Term matching is whole-word and case-insensitive.
- **`PiiRedactionGuardrail`** is rebuilt on `BuiltinRecognizers`.
  - `patterns` selects kinds. When it is absent, the standard set runs, which is the original four kinds plus `iban`.
  - The new `countries` parameter adds every identifier of the listed countries. The two combine: `{"patterns": "email", "countries": "DE,FR"}`.
  - The kinds run one after another in a fixed order. E-mail runs first, so an address whose local part is an IBAN or a tax ID is redacted whole. The checked national formats come next, and the loose card and phone patterns run last, so they cannot take an identifier's digit groups (before, `DE89 3704 0044 0532…` came out as a partial `[REDACTED:phone]`).
  - The four original kinds keep their names, their `{kind}` value and their reason, and their output is byte-identical. `PiiRedactionGuardrailTest` checks this against `LegacyPiiRedaction`, a frozen copy of the old rewrite, over a fixed corpus and 3,000 random fragment combinations.
  - An unknown kind or country is now a deploy warning. Before, an unknown kind selected nothing, silently.
- **Manager:**
  - The PII section lists the universal kinds, then the 27 countries grouped by region. Ticking a country writes it to `countries`; ticking one of its identifiers writes that to `patterns`.
  - Country names come from `Intl.DisplayNames` in the UI language. Identifiers show their official names (PESEL, NIR, Codice fiscale, …).
  - Labels are in all 11 locales. A stored `patterns` that uses the entity-name aliases `CREDIT_CARD` or `US_SSN`, or that is whitespace only, ticks the right boxes.
- **Operator:** the system prompt describes `patterns`, `countries` and the opt-in rule; the fingerprint for revision 53 is updated.
- **Docs:** a new [PII kinds and countries](../langchain.md#pii-kinds-and-countries) section with the full table; [`security.md`](../security.md).

### Design decisions

- **National identifiers are opt-in.** Each one matches only its own country's format. Turning all 27 countries on by default would only add false positives in texts that never mention them. A compact 15-digit French NIR, for example, also passes the card Luhn check. The standard set stays what agents always had, plus the international IBAN.
- **Weak check digits need a keyword.** Where a check digit lets about one in ten random numbers of the right length through, a keyword must stand within 40 characters before the value. This applies to PESEL, NHS, BSN, NIF, SVNR, CPR, SIN, Aadhaar, My Number, TFN, Medicare, IRD and the German ID card, and to dates of birth. Identifiers with a strong check or a distinctive shape need none.
- **No check digit where the issuer dropped it.** Korean RRNs issued since October 2020 and Danish CPR numbers since 2007 carry no reliable check digit, so only their date and structure are checked, and CPR also needs a keyword.
- **Not included:** EU VAT numbers and Brazilian CNPJs, which identify businesses rather than people.
- **The guardrail applies kinds sequentially** rather than through the merger. Its replacement text is author-configurable, and the legacy result depends on later kinds seeing earlier replacements, so only sequential application keeps the output byte-identical. The merger is for pseudonymization, which replaces each value with its own token.
- **The detector honours an explicit request.** When `DetectorContext.entities()` names types, those run even if they are outside the standard set. The standard set is the default only when nothing is requested.
- **No new regex budget class, and no budget on the guardrail.** `RegexDeadline` already provides one, and `BuiltinPiiDetector` honours the caller's deadline, which a pseudonymization chain will need. Every pattern is bounded, so a scan is linear; a test runs all 40 recognizers over 700,000 characters of adversarial digit, letter, separator and keyword runs within 10 seconds. `regex-filter` keeps its budget because its patterns are the author's.
- **The package is `modules/llm/pii`,** not `modules/llm/pseudonymization` as the plan said, because the detectors serve both features.

### Next

PR 2 of the pseudonymization plan: the token codec, key provider, session and restorer, unit-tested and not yet wired into `LlmTask`.
