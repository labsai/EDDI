## ⚡ perf(startup): derive the audit keys while the vault derives its KEK (2026-10-18)

**Repo:** EDDI (`perf/startup-parallel-kdf`)

### What changed and why

Thread dumps of the main thread during boot showed it in PBKDF2 twice, one
after the other: the audit ledger's HMAC key (`AuditKeyring.initialize` →
`AuditHmac.deriveHmacKey`) and the vault KEK (`EnvelopeCrypto.deriveKeyFromString`
from `VaultSecretProvider.onStartup`). Each is 600,000 iterations of
HMAC-SHA256, a few hundred milliseconds, and they share no input.

- **`AuditKeyring.deriveEarly`** is a new `StartupEvent` observer at
  `APPLICATION - 100`, ahead of the vault's at `APPLICATION`. It starts
  `initialize()` on a virtual thread and returns, so the audit keys are derived
  while the main thread loads the vault salt and derives the KEK.
- **The join is the existing lock.** `AuditKeyring.onStartup` (`APPLICATION + 100`)
  and every key accessor call `initialize()`, which waits on the keyring's
  monitor until the derivation is done. The volatile `initialized` fast path and
  the `synchronized` double-check are unchanged, so the keys are complete before
  readiness and before the first entry is signed.
- **`initialize()` is no longer a `@PostConstruct`.** Arc instantiates the bean
  on the thread that first touches it — the observer's, the main one — so a
  `@PostConstruct` derivation would have run inline again. Every method that
  reads a key already called `initialize()` first; `fromMasterKey` still does.
- **Failure is not swallowed.** If the early derivation throws, `initialized`
  stays false and the next caller derives again on its own thread and sees the
  exception, as before.

The KDF, salts, iteration count and therefore every key are unchanged:
existing signatures and vault entries verify as before.

### Tests

`StartupKeyDerivationTest` pins both derivations to known answers computed
outside the JVM (Python `hashlib.pbkdf2_hmac`), checks that keys derived through
`deriveEarly` equal those from an inline `initialize()`, and that `deriveEarly`
returns without doing the work itself. Mutation-checked: 600,001 iterations in
`AuditHmac` or in `EnvelopeCrypto`, and running the early derivation inline, each
fail it.

### Measured

Fast-jar boots of `origin/main` and this branch, built with `-DskipUi=true`,
against a local MongoDB 7 with a vault master key set, 8 runs each in
alternating order after one discarded warm-up (Windows 11, JDK 25.0.1, a host
shared with other work):

| | `started in` median (min) | to `/q/health/ready` median (min) |
|---|---|---|
| before | 4.03 s (3.51 s) | 4.90 s (4.42 s) |
| after | 3.52 s (3.17 s) | 4.48 s (4.21 s) |

About 0.4–0.5 s off a ~4 s boot. The spread between runs is of the same
order, so read it as "one PBKDF2 derivation leaves the critical path", not as
a precise figure. On a cold, contended container the derivations are slower,
and so is the saving.
