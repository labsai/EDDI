## 📝 docs(upgrading): say that stored conversations and config history keep 5.x credentials (2026-09-30)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### Why

[`upgrading-from-5x.md`](../upgrading-from-5x.md) §7, "What is not migrated automatically", listed
plaintext credentials in agent configs, but not the copies EDDI 5 left elsewhere. EDDI 5 stored
whatever a turn carried, and no migration rewrites stored conversations. A rehearsal against a
production 5.5.1 database found credential-named fields in every one of its 195 conversations:
- properties copied from plaintext configs;
- a user token sent as context, in 174 of them;
- recorded `Authorization` request headers, in 93.

The config `.history` collections kept the plaintext as well. An operator following the guide would
have believed the upgrade left no credentials outside the vault and the named backups.

### What changed

- §7 now names both leftovers, stored conversations and the config history, and says why rotating
  every credential a 5.x deployment used is the only complete fix.
- A `mongosh` script counts credential-named fields per collection and prints counts and field names,
  never values. It walks every nested object and array, so it doesn't depend on the stored shape, and
  it also catches property instructions that name the credential in a value (`{name: "…",
  valueString: …}`), which is how plaintext keys sit in property-setter configs. Verified against the
  5.5.1 dump: it found every location found by hand during the staging migration (the conversations,
  API-call and LLM configs and their history, the property setters and their history).
- §8 points back to it.
