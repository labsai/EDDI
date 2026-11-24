## 🧪 test(operator): upgrade a real previous-release operator, and run the operator under Keycloak, in CI (2026-11-24)

**Repo:** EDDI (`test/operator-e2e-real-upgrade-and-oidc`, on top of `fix/operator-upgrade-hardening`)

### What changed and why

The operator's real-backend E2E tier activated it against the image under test,
and covered an upgrade only synthetically: the spec rewrote a current operator's
stored config to claim an older revision. That cannot reproduce what a real
older operator leaves behind (the older Manager's stored fields, the older tool
shapes and gate baked into its agent, the grant its own grant prompt wrote), and
no tier ran the operator with OIDC on, where a tool call without the caller's
token is refused. Two Playwright projects close both gaps.

1. **`operator-upgrade`**
   ([`operator-real-upgrade.upgrade.spec.ts`](../../ui/manager/e2e/operator/operator-real-upgrade.upgrade.spec.ts)).
   It starts the previous release's image on a fresh MongoDB volume beside the
   model stub. It activates the operator through THAT release's own Manager
   (read_write, the stub model, a vault key restricted to a bystander agent, so the
   old grant prompt runs and is confirmed without "allow every agent") and sees it
   answer a tool call. Then it stops the container, starts the image under test on
   the same volume, and upgrades through the current Manager. It asserts the
   banner; the instructions choice; no grant prompt or allow-all at any point; the
   new agent deployed and gate-verified with no stale banner and no reload; the
   write canary passing; the deleted agent gone from the grant and the bystander
   still on it; and a real tool call through the stub. It runs twice: with the
   default instructions untouched (replaced, no choice offered) and with a line
   the administrator added in the old Manager (Keep preselected, the line shown
   as added in the review diff, and the line reaching the model afterwards). The
   spec drives `docker compose` itself, under project `eddi-operator-upgrade`,
   with [`docker-compose.integration-operator-upgrade.yml`](../../ui/manager/docker-compose.integration-operator-upgrade.yml)
   on top of the operator stack. That overlay sets
   `EDDI_VAULT_ALLOW_PLAINTEXT_REMOTE_HOSTS`: the vault otherwise refuses to send
   the key over http to the stub, and every model call fails.
2. **The release it upgrades from is pinned** in
   [`previous-release.json`](../../ui/manager/e2e/operator/previous-release.json)
   (`labsai/eddi:6.7.1`, revision 62 against this branch's 82). It is not derived
   from the Helm `appVersion` release pointer: on `origin/main` it still read
   `6.6.0` when this was written, two releases behind, so it would have started
   the upgrade from the wrong release. A live
   GitHub lookup was rejected so a run does not depend on the network or change
   under a PR. [`release-versioning.md`](../release-versioning.md) ("After a GA
   Release") now says to bump it. If the pinned release's operator revision equals
   this Manager's, the spec lowers the stored revision by one and records an
   annotation instead of failing.
3. **`operator-oidc`**
   ([`operator-caller-identity.oidc.spec.ts`](../../ui/manager/e2e/operator/operator-caller-identity.oidc.spec.ts)).
   It runs on the Keycloak stack with the operator overlay layered on it
   (`docker-compose.integration-keycloak.yml` + `docker-compose.integration-operator.yml`,
   neither changed). The administrator signs in on Keycloak's login page and
   activates with `authMode: "caller-identity"`. The spec asserts the gate is
   verified, both canaries pass, and a read tool call answers. A gated descriptor
   write on a bystander agent, approved in the operator chat, must then appear in
   `GET /auditstore/admin-actions` under the same principal (`eddi`) as the user's
   own REST call. Without the token the call is refused before it reaches the
   endpoint, so it would not be recorded.
4. **The model stub misread every failed tool call as a success.** The engine now
   wraps a tool result as `[tool result — tool 'x', source 'http'. …] {…} [end of
   tool result]`, and [`llm-stub.mjs`](../../ui/manager/e2e/operator/llm-stub.mjs)
   only treated a result as a failure when it *started* with `{"error":`. So
   `OPERATOR_E2E_TOOL_FAILED` could not occur, and the "the tool call failed"
   assertions in all three tiers passed with the call refused. It surfaced when the
   OIDC tier ran with caller identity off and the read still reported OK. The stub
   now reads the payload inside the envelope. The activation tier still passes
   with the fix. The stub also gained `GET /__e2e/system-prompt?contains=…`, which
   returns only a verdict and a length.

### CI

- `Operator Activation E2E` (`e2e-operator`): after the activation tier, the
  activation stack is torn down (its report moved to `playwright-report-activation/`)
  and the new step *Upgrade a real older operator* runs `npm run
  test:e2e:operator-upgrade`. The timeout went from 20 to 35 minutes. The per-cycle
  compose logs are uploaded from `ui/manager/test-results/`.
- `Auth E2E (Keycloak)` (`e2e-auth`): the stack now layers the operator overlay,
  and the new step *Activate the operator under Keycloak* runs `npm run
  test:e2e:operator-oidc` after the auth tier, on the same stack, with `PORT=7070`
  so the onboarding seed applies to the Manager EDDI serves.
- Both jobs already feed `E2E Gate`, and both run on `code`, which `ui/**` and
  `.github/workflows/**` trigger. No new job was added.

### Local runs (Windows, Docker Desktop, image built from this branch)

- `operator-upgrade`: 2 passed, 2.2 to 2.5 minutes for both cycles, including four
  container starts (3.8 minutes once, with lint and a Maven run sharing the
  machine). CI adds the previous release's image pull.
- `operator-oidc`: 1 passed in 24 s, on a Keycloak stack that took about 55 to 90 s
  to come up.
- `operator` (activation tier, with the fixed stub): 2 passed in 27 s.

### Mutation checks

- **Test A, grant carry-over reverted** (`carryOverVaultGrants` skipped in
  `use-operator.ts`, then the jar and image rebuilt): the default cycle failed with
  `the upgrade did not finish — Received: "stopped on the vault grant dialog"`.
  The spec names that dialog rather than waiting out the 180 s timeout.
- **Test B, caller identity off** (`EDDI_CALLER_IDENTITY_ENABLED=false` on the
  backend, same image). Before the stub fix, the read call and the approved write
  both reported OK, and only the audit assertion failed (expected 1 record,
  received 0). After it, the spec fails at the read call: `{"error": "This API
  call references ${caller:...}, but caller-identity forwarding is disabled"}`.
  Activation and both canaries passed in that mutant (see Open).
- Both specs pass again with the mutations removed. The auth tier (28 tests)
  passes on the layered Keycloak stack, run before the OIDC operator spec as CI
  runs it.

### Open

- The read canary passed with caller identity switched off: it counts tool calls
  and does not check that the call succeeded. The OIDC tier catches the failure
  at the next tool call, but activation itself reports success.
- `previous-release.json` is bumped by hand. Folding it into
  `bump-version.py post-release` would make it automatic, but that script's
  pointer walk is mirrored in Java (`BumpVersionScriptTest`).
