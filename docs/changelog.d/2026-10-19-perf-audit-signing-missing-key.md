## perf(audit): remember a missing agent signing key instead of reading the vault per audit entry (2026-10-19)

**Repo:** EDDI · **Branch:** `perf/audit-signing-missing-key`

### What changed and why

An investigation of throughput collapses under a 5,000-user burst
([`scripts/loadtest/results/collapse-2026-10-19.md`](../../scripts/loadtest/results/collapse-2026-10-19.md))
found that every audit entry did a vault read that was certain to miss. `AuditLedgerService.submit`
signs each entry with the agent's Ed25519 key, on the turn's own thread. Almost no agent has a key:
`AgentSigningService.generateKeyPair` has no production caller, so a key exists only where an operator
wrote one into the vault by hand. Each entry therefore queried `secretvault_secrets`, got
`SecretNotFoundException` and threw two more exceptions on the way out, about three times per turn.

- `AgentSigningService` remembers a vault miss for 60 s (`MISSING_KEY_RECHECK`) in a Caffeine cache
  capped at 10,000 keys (`MAX_MISSING_KEYS`). The cap is there because an audit entry can name any
  well-formed agent id. `generateKeyPair`, `generateKeyPairVersioned` (for that version) and
  `deleteKeyPair` clear the marker at once on the node that ran them. A key written into the vault by
  hand, possibly through another node, is used within a minute. Only `SecretNotFoundException` is
  remembered: a vault that could not answer is asked again on the next call.
- New `signIfKeyPresent`, used by the audit ledger, returns `null` for a known-missing key without
  throwing. `sign` keeps its contract and still throws.
- Measured with `loadtest.py gate` (PR #1086): `secretvault_secrets` queries per turn went from 3.0 to
  0, and all queries per turn from 17.5 to 13.96, with all 100 turns succeeding.
- [`docs/configuration-reference.md`](../configuration-reference.md) described
  `eddi.audit.agent-signing-enabled` as signing agent configurations. It signs audit entries, and the
  row now says so, including the 60 s re-check.

### Design decisions

- **A time-bounded marker, not a check of the agent's `identity.publicKey`.** The public key in the
  configuration and the private key in the vault are set independently, and reading the agent
  configuration per entry would cost what this removes. 60 s bounds how long a newly written key goes
  unused.
- Tests: `AgentSigningServiceTest$MissingKey` (nine cases, including the size bound and versioned generation). Mutation-checked: dropping the marker fails
  three of them, and caching vault outages as misses fails the outage case.

### What's next

- The investigation's follow-ups are listed in the results file: the 60 s turn timeout not firing under
  event-loop saturation, `/q/metrics` going unresponsive during a burst, and turns that run after their
  caller has gone. The start-path capacity check went to PR #1085.
