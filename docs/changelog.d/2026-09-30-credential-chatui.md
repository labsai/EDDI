## fix(migration): identifiers are no longer held back as credentials by the legacy-properties migration (2026-09-30)

**Repo:** EDDI (`fix/650-credential-chatui`)

### Why

On a 5.5.1 database, `PropertiesMigrationService` held back five `createdProgram` properties whose
value is `{courseId: <a 17-character alphanumeric id>}` and logged them as credentials. The rule
that fired is the entropy check in `SecretScrubber.scrubTextValue` (check 3): a key-like string of
at least 14 characters scoring over 3.5 bits per character is redacted unless its field is one of
the structural names. A random 17-character id scores about 4.1, and `courseId` is not structural.
The values stayed in `properties_migrated_v6`, but the users lost those memories.

### What changed

- [`PropertiesMigrationService`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java):
  the copy of the value handed to the scrubber (which already replaced BSON ObjectIds) now also
  replaces an **identifier-shaped** string (`[A-Za-z0-9_-]{1,128}`) under an **identifier-named**
  field — `id`, or a name whose last word is `id`/`ids` (`courseId`, `course_id`, `courseID`,
  `courseIds`) — with a placeholder. Names qualified by a credential word (`sessionId`,
  `accessKeyId`, `tokenId`, `apiId`) get no exemption.
- A new **known-format** check holds back any key whose value contains, at any depth, a JWT, a
  pasted `Bearer`/`Basic` value, or a provider key prefix (`sk-`, Stripe, Slack, GitHub, GitLab,
  AWS, Google, Hugging Face). It closes a gap the entropy rule never covered: `"Bearer …"` has a
  space, so the scrubber's whole-value pattern did not match it. An identifier-named field gets no
  exemption for these.
- The credential-name rules are unchanged, and the scrubber itself is untouched: the narrowing
  applies to this migration only, so export scrubbing is exactly as strict as before.

### Tests

`PropertiesMigrationServiceTest$IdentifiersAreNotCredentials`: the id migrates (with a
precondition test proving the scrubber alone still flags it); name variants migrate; `token`,
`apiKey`, a JWT, a `Bearer` value, an `sk-` key, a JWT under `courseId`, a high-entropy value under
a neutral key and under `sessionId` are all held back and logged by key name only; `userInfo` is
still skipped. Name- and format-rule cases use zero-entropy values so entropy cannot catch them
for the wrong reason. Mutation-checked.

### Not changed

The export scrubber has the same false positive: any identifier of 14+ random characters under a
non-structural field name is redacted on export. Loosening it globally is not shown to be safe
everywhere, so it is left as it is.
