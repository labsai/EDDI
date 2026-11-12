## 🐛 fix(apicalls): redact encoded echoes of a substituted secret (2026-11-09)

**Repo:** EDDI (`fix/redact-encoded-secret-echoes`)

### What changed and why

External security review finding B11 (low). Response redaction for httpcalls is by value: the
executor knows every plaintext it substituted into a request and removes it from the response
before the response reaches conversation memory, template data, the tool result or the log. It
removed exact occurrences only. A server that echoed the secret header back verbatim was redacted;
URL-encoded it was redacted only because the tester's value was alphanumeric; **base64-encoded it was
stored in conversation memory as it came**.

`SecretEncodings` (new, next to `SecretValueScrubber`) expands a set of secrets into the needle set
the scrubbers search for: every value of at least the minimum length also contributes its base64
(standard and URL-safe, padded and unpadded), percent-encoding (`+` or `%20`, upper or lower hex,
`~` escaped or literal), JSON string escaping (`/` escaped or not, non-ASCII literal or `\u`-escaped
in either case) and HTML entity forms, and a whole `Basic <base64>` credential also contributes its
decoded `user:password` and password. The set is built once per redaction and handed to the existing
scrubber, so the walk itself is unchanged.

`ApiCallExecutor` uses it in four places, each a one-line change in the helper section: the success
text and the long-secret tree pass (floor 8, `MIN_SUCCESS_BODY_REDACTION_LENGTH`), the short-secret
exact pass (floor 4, `MIN_SEARCHED_SECRET_INPUT_LENGTH` — whole-value matches only), response header
values and the error body (floor 8 for the encoded forms; the plaintext itself is still redacted at
every length there).

The error body goes through one helper, `redactErrorBody`, for a single call **and** for every
`forEach` item. The item path used to redact its error body with the plaintexts only, so an item
whose 401 echoed the key base64- or URL-encoded was stored with the echo while the same response to a
single call was redacted; `ApiCallExecutorForEachTest` now covers both forms and asserts the two
paths store the same text.

### Design decisions

- **Same floor for the forms as for the value.** Every encoded form is at least as long as the value
  it encodes, so the 8-character floor that keeps a short username from being rewritten inside
  ordinary text also bounds its encodings. A short secret's forms are matched only where a JSON value
  *is* one — the rule its plaintext already follows.
- **Not covered, documented instead:** reversed or transformed values, hex/base32/double encoding,
  and a secret inside a longer base64 blob (`base64(user:secret)` does not contain
  `base64(secret)`). The redaction protects later readers of a conversation from an accidental
  echo; it is not a defence against the target server.
- The change lives in a new helper rather than in `SecretValueScrubber` or `SecretScrubber`, and the
  `ApiCallExecutor` edits stay in its helper section, away from open PRs touching those files.

**Files:** [`SecretEncodings.java`](../../src/main/java/ai/labs/eddi/engine/memory/SecretEncodings.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`SecretEncodingsTest.java`](../../src/test/java/ai/labs/eddi/engine/memory/SecretEncodingsTest.java),
[`ApiCallExecutorEncodedEchoRedactionTest.java`](../../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutorEncodedEchoRedactionTest.java),
[`ApiCallExecutorConfigReferenceTest.java`](../../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutorConfigReferenceTest.java),
[`ApiCallExecutorForEachTest.java`](../../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutorForEachTest.java),
[`secrets-vault.md`](../secrets-vault.md)
