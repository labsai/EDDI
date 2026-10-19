## perf(audit): the audit ledger signs through signIfKeyPresent; burst-collapse investigation (2026-10-19)

**Repo:** EDDI · **Branch:** `perf/audit-signing-missing-key`

### What changed and why

An investigation of throughput collapses under a 5,000-user burst
([`scripts/loadtest/results/collapse-2026-10-19.md`](../../scripts/loadtest/results/collapse-2026-10-19.md))
found that every audit entry did a vault read that was certain to miss. `AuditLedgerService.submit`
signs each entry with the agent's Ed25519 key, on the turn's own thread, and almost no agent has a key.
This branch fixed that with a 60 s missing-key cache. Meanwhile #1055 landed an equivalent cache on
`main` (`AgentSigningService.missingKeyUntil`, with a guard against a lookup racing a key generation),
so the merge keeps `main`'s implementation. What this branch still adds:

- `AgentSigningService.signIfKeyPresent`, used by the audit ledger. While a "no key" answer is
  remembered it returns `null` without throwing. Through `sign`, a remembered miss still builds three
  exceptions per entry (`SecretNotFoundException`, `PrivateKeyLoadException`, `AgentSigningException`),
  about 8,000 of each in one profiled burst. `sign` keeps its contract.
- The investigation's results file. Host contention triggers the collapses; on 2026-10-18 that is
  stated as a hypothesis, since no host telemetry covers the worst runs. On scarce CPU, EDDI spent most
  of it outside turns. Its follow-ups are the unfired 60 s turn timeout, `/q/metrics` going unresponsive
  and turns that run after their caller has gone.
- [`docs/configuration-reference.md`](../configuration-reference.md) described
  `eddi.audit.agent-signing-enabled` as signing agent configurations. It signs audit entries, and the
  row now says so, including the 60 s re-check.

### Design decisions

- On the merge conflict with #1055, `main`'s cache won outright: it also guards the race in which a
  lookup that started before `generateKeyPair` publishes its miss afterwards. This branch's own cache, a
  Caffeine cache with a 10,000-key cap that came out of review, was dropped rather than merged in.
- Tests: three `signIfKeyPresent` cases in `AgentSigningServiceTest` (null without a second vault read,
  a present key, a vault failure still thrown), and `AuditLedgerServiceBranchTest` covering the
  unsigned-entry path.
