## docs(security): cryptography inventory and post-quantum readiness, guarded by a test (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/pqc-crypto-inventory`

First step (PQ-1) of the post-quantum readiness work. It changes no runtime behaviour.

### What changed and why

- **New page [`docs/cryptography.md`](../cryptography.md)** lists every algorithm EDDI uses (vault AES-256-GCM and PBKDF2, the HMAC-SHA256 audit ledger and vault checksums, Ed25519 agent signing, SHA-256 hashing, and TLS), what each one protects, and whether it is post-quantum safe. It also covers the threat model (harvest-now-decrypt-later for TLS, future forgery of Ed25519 signatures) and the roadmap. The page is linked from [`security.md`](../security.md) (TLS section and See Also) and from `SUMMARY.md`.
- **Verified on JDK 25.0.1:** JSSE's default named groups are classical only (`x25519, secp256r1, secp384r1, secp521r1, x448, ffdhe*`), while `ML-DSA-65` and `ML-KEM-768` are available through the standard JCA (an ML-DSA-65 signature is 3309 B and its public key 1974 B). The page states only these verified facts about TLS.
- **New `CryptoInventoryTest`** scans `src/main/java` for the JCA engine `getInstance(...)` calls and `new SecretKeySpec(...)`. It resolves each algorithm from a literal or a same-file `static final String`, and fails the build in four cases:
  - the algorithm is not on its allowlist;
  - the argument cannot be resolved, so moving the string into a variable cannot bypass the check;
  - the call pins a JCA provider;
  - an allowlist entry is no longer used, or is not named in `docs/cryptography.md`.

### Design decisions

- Rather than scan only the per-class constants listed in the plan, the test resolves each call's argument, so a new call site cannot slip through by using an unfamiliar constant name.
- `TrustManagerFactory`, `KeyManagerFactory` and `KeyStore` are left out of the scan: they name PKIX and trust-store formats rather than primitives.
- The allowlist is two-way (unused entries fail as well) so the doc cannot drift from the code.

### Next

PQ-5a: make the existing agent signatures on audit entries versioned and verifiable (see `planning/pqc-readiness-plan.md`).
