# Cryptography and Post-Quantum Readiness

> **Status:** Inventory current as of 2026-10-15. Guarded by `CryptoInventoryTest`.

This page lists every cryptographic algorithm EDDI uses, what each one protects, and how exposed it is to a future quantum computer. It also describes what EDDI is doing about that exposure.

**The inventory is enforced, not just documented.** `CryptoInventoryTest` scans `src/main/java` for every algorithm requested from the Java Cryptography Architecture (`Cipher`, `Signature`, `KeyPairGenerator`, `KeyFactory`, `Mac`, `SecretKeyFactory`, `KeyAgreement`, `KEM`, `MessageDigest`, `KeyGenerator`, `SSLContext` and `new SecretKeySpec`). The build fails when:

- the code uses an algorithm that is not listed here;
- an algorithm argument cannot be resolved statically;
- the code pins a JCA provider;
- an entry here is no longer used anywhere.

To add an algorithm, update the table below and the test's allowlist in the same change.

## Threat model

A cryptographically relevant quantum computer would break today's public-key cryptography, such as RSA, elliptic-curve Diffie-Hellman and Ed25519/ECDSA signatures. It would not meaningfully break symmetric ciphers and hashes, because Grover's algorithm only halves their effective strength (AES-256 still offers about 128 bits).

Two attacks follow from that:

| Threat | What it breaks | Urgency for EDDI |
|---|---|---|
| **Harvest now, decrypt later.** Traffic is recorded today and decrypted once a quantum computer exists. | The confidentiality of anything sent over TLS that uses a classical key exchange: conversation content (which may include personal or health data), LLM provider API keys, bearer tokens. | **Now.** Data recorded today stays exposed for as long as it stays sensitive. |
| **Future signature forgery** | Ed25519/ECDSA/RSA signatures, including agent identity envelopes, agent signatures on audit entries, and OIDC tokens. | **Medium term.** It matters most for long-lived evidence such as the audit ledger, which is kept for years. |

**Regulatory direction.** NIST IR 8547 (draft) proposes deprecating quantum-vulnerable public-key algorithms after 2030 and disallowing them after 2035. The EU's coordinated post-quantum roadmap asks member states to start the transition by the end of 2026 and to protect high-risk systems by 2030. Check the current versions of both before relying on these dates.

## Inventory

| Use | Algorithm | Where | Post-quantum status |
|---|---|---|---|
| Secrets vault: envelope encryption of secrets (DEK) and of DEKs (KEK) | AES-256-GCM (`AES/GCM/NoPadding`, keys from `KeyGenerator` `AES`), 12-byte IV, 128-bit tag | `secrets/crypto/EnvelopeCrypto` | ✅ Symmetric, 256-bit key |
| Vault KEK derivation from `EDDI_VAULT_MASTER_KEY` | `PBKDF2WithHmacSHA256`, 600,000 iterations, per-deployment random salt | `secrets/crypto/EnvelopeCrypto`, `VaultSaltManager` | ✅ |
| Vault value checksums (dedup / match without decrypting) | `HmacSHA256` keyed with a random per-deployment key, over tenant id and value. Legacy rows hold a bare `SHA-256`. | `secrets/crypto/VaultChecksum` | ✅ |
| Audit ledger entry integrity | `HmacSHA256` over a versioned canonical form of each entry. The key is configured (`eddi.audit.hmac-key`) or derived from the vault master key with `PBKDF2WithHmacSHA256` and then pinned in the vault (see [Audit Ledger](audit-ledger.md)). | `engine/audit/AuditHmac`, `AuditKeyring` | ✅ Quantum-safe, but symmetric: only a key holder can verify, not a third party |
| Audit ledger keyed pseudonyms for erased users | `HmacSHA256` | `engine/audit/AuditHmac` | ✅ |
| Agent identity and signed inter-agent envelopes | **Ed25519** | `configs/agents/AgentSigningService`, `configs/agents/crypto/SignedEnvelope`, `engine/internal/groups/GroupSigningGuard` | ❌ Classical signature |
| Agent signature on audit entries | **Ed25519**, over the entry's HMAC | `engine/audit/AuditLedgerService` (via `AgentSigningService`) | ❌ Classical signature |
| Slack request verification | `HmacSHA256` (scheme defined by Slack) | `integrations/slack/SlackSignatureVerifier` | ✅ |
| Inbound mail sender authentication: DKIM signature **verification** only (EDDI never signs mail) | RSA-SHA256 (`Signature` `SHA256withRSA`, keys from `KeyFactory` `RSA`, at least 1024 bits; `rsa-sha1` refused) and **Ed25519** (RFC 8463), body hash `SHA-256` | `engine/mail/DkimVerifier` | ❌ Classical signature, chosen by the sending domain rather than by EDDI |
| Content hashes, cache keys, idempotency and dedup keys | `SHA-256` | `MessageDigest` call sites across the codebase | ✅ Not used for secrecy |
| NATS client TLS context | `SSLContext` `TLS` with the JDK's default trust and key managers | `engine/cluster/NatsConnectionManager` | ❌ Classical key exchange, from the JDK (see below) |
| OIDC / JWT verification | RS256, ES256 and similar, as chosen by the issuer (for example Keycloak) | `quarkus-oidc` configuration | ❌ Classical signature, decided by the identity provider rather than by EDDI |
| Inbound TLS (HTTP server) | JDK JSSE through Vert.x | `quarkus.http.ssl*` configuration, or a reverse proxy | ❌ Classical key exchange |
| Outbound TLS (LLM providers, HTTP calls, MCP, A2A, Slack, agent sync) | JDK JSSE (`java.net.http.HttpClient`) and Vert.x | — | ❌ Classical key exchange |
| Database and NATS TLS | MongoDB, PostgreSQL and NATS drivers on JDK JSSE | Connection strings | ❌ Classical key exchange |

All algorithms come from the JDK's own providers. BouncyCastle is on the classpath only as a transitive dependency of the NATS client, and EDDI does not request it.

## What is quantum-safe today

**Safe:** everything symmetric, which covers the secrets vault, the vault checksums, the HMAC integrity of the audit ledger, and Slack verification.

**Not yet safe:** the Ed25519 agent signatures, and every TLS connection. TLS is the more urgent of the two, because it is exposed to harvest-now-decrypt-later.

### TLS key exchange

EDDI runs on JDK 25. Its TLS stack (JSSE) offers only classical key-exchange groups by default:

```text
x25519, secp256r1, secp384r1, secp521r1, x448, ffdhe2048 … ffdhe8192
```

No hybrid post-quantum group such as `X25519MLKEM768` is available in JSSE on this JDK. The JDK *does* already ship the post-quantum primitives: ML-KEM (FIPS 203) and ML-DSA (FIPS 204) have been available through the standard JCA since JDK 24. Only their use inside TLS is missing.

Until EDDI runs on a JDK whose JSSE offers a hybrid group:

- **Inbound:** to get post-quantum key exchange today, terminate TLS at a reverse proxy or load balancer that supports `X25519MLKEM768`, such as one built on OpenSSL 3.5 or later, or BoringSSL. Terminating TLS at a proxy is already the recommended deployment (see [Security → TLS Requirements](security.md#tls-requirements)). Post-quantum TLS at EDDI itself protects nothing if a classical proxy sits in front of it.
- **Outbound:** connections from EDDI to LLM providers, MCP servers, databases and NATS use the JDK's defaults. They will offer a hybrid group once the base image moves to a JDK that ships one, with no code change.

## Roadmap

EDDI's post-quantum work is aimed at these outcomes:

1. **A maintained inventory.** This page, together with `CryptoInventoryTest`.
2. **Agent signatures that can actually be verified.** Agent signatures on audit entries will record which key version produced them, protect that version against tampering, and be checked by the audit verification endpoints.
3. **Crypto-agile agent signing.** Agent keys will be able to use ML-DSA-65 or a hybrid of Ed25519 and ML-DSA-65. A per-agent *minimum algorithm* policy will reject signatures from weaker keys, so that an attacker who can forge Ed25519 cannot downgrade a migrated agent. Ed25519 stays the default.
4. **Audit evidence that third parties can verify.** Optional checkpoints will sign a Merkle root of publicly recomputable digests of the entries with a post-quantum key. An auditor can then check the ledger offline with a public key pinned out of band, which the symmetric HMAC alone does not allow.
5. **TLS visibility.** EDDI will log at startup whether hybrid post-quantum key exchange is available, and the documentation will give verified guidance for inbound TLS.

The ledger's HMAC canonical forms are frozen and will not change as part of this work. Existing configurations and stored data will keep verifying exactly as before.

## See Also

- [Security](security.md): authentication, SSRF protection, TLS requirements
- [Secrets Vault](secrets-vault.md): envelope encryption and key rotation
- [Audit Ledger](audit-ledger.md): HMAC signing, key rotation, verification
- [Group Conversations](group-conversations.md): signed inter-agent messages
