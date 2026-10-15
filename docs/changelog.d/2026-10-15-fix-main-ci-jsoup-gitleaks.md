## fix(ci): unblock main — shaded jsoup CVE in jinjava, gitleaks false positives, a time-of-day schedules test flake (2026-10-15)

**Repo:** EDDI · **Branch:** `fix/main-ci-jsoup-gitleaks`

### What failed

- **Docker Build & Push** on main (`f3d0fa596`, `37f8292fd`): the Trivy image scan
  reported one HIGH, CVE-2026-75140 (GHSA-65r4-943x-97jj, quadratic memory in jsoup's
  `XmlTreeBuilder`, fixed in 1.23.2) in `org.jsoup:jsoup` 1.15.3 inside
  `com.hubspot.jinjava.jinjava-2.8.4.jar`.
- **Secret Scanning** on main (`775edae28`, the squash of PR #956): gitleaks 8.30.1
  reported three `generic-api-key` findings.
- **UI Manager Checks** on main (`775edae28`): `schedules.test.tsx` "shows schedule
  names from mock data" threw "Found multiple elements" for `Daily Health Check`.

### What changed and why

- **`.trivyignore`** — the jsoup copy is *shaded*: jinjava's `maven-shade-plugin`
  bundles jsoup 1.15.3 into its own jar and relocates it to `jinjava.org.jsoup`, so a
  managed `org.jsoup:jsoup` version cannot replace it, and 2.8.4 is still the newest
  jinjava on Maven Central. jinjava is on the classpath only because `jlama-core`
  renders model chat templates with it, so it cannot be excluded without breaking the
  `jlama` provider. The vulnerable code is not reachable: jinjava's only jsoup users are
  the `closehtml`, `striptags` and `truncatehtml` filters, which use the HTML parser,
  and no jinjava or jlama class references the XML parser. EDDI's own direct jsoup is
  already 1.23.2. The entry carries `exp:2027-01-15` so it lapses and forces a re-check
  rather than silently outliving a jinjava fix.
- **`.gitleaksignore`** — the three findings are rows of the Manager's MSW cluster mock
  (`ui/manager/src/test/mocks/cluster-handlers.ts`, lines 83–85), whose `key` field is a
  NATS KV lease key made of `c.` and a made-up conversation id. Fingerprints for the
  squash commit `775edae28` were taken from a local run of gitleaks 8.30.1 (checksum
  verified against the release) over `37f8292fd..775edae28`, the range main's push scan
  checked. The `scripts/cluster-demo/demo.py` demo values were not flagged.
- **`ui/manager/src/pages/__tests__/schedules.test.tsx`** — a time-of-day flake, not a
  regression. The MSW mock gives Heartbeat Monitor `nextFire = now + 30 min` and Daily
  Health Check the next weekday 09:00 UTC, so from 08:30 to 09:00 UTC on a weekday
  Daily Health Check is the soonest schedule and its name renders a second time, in the
  Next Fire card. The test now uses `findAllByText`, like the Heartbeat Monitor line
  below it already did. The other tests naming either schedule were already safe
  (`schedules-features.test.tsx:171` reads an input inside the edit dialog; the Next
  Fire card test matches either name).

### Verification

- Trivy 0.70.0 (the version the pinned `trivy-action` installs) over the jinjava jar:
  1 HIGH without the entry, exit 0 with it.
- gitleaks 8.30.1 over `37f8292fd..775edae28`: 3 leaks before, none after.
- `schedules.test.tsx` with the clock faked to a Wednesday 08:45 UTC: the old assertion
  fails with "Found multiple elements", the new one passes (50/50); it also failed
  unfaked at 08:57 UTC on the day of the fix, and both schedules test files pass with
  the fix (65/65). `npm run typecheck` and `npm run lint` are clean.

### Decision

The `.trivyignore` entry is the last resort, taken because there is no fixed jinjava,
no override can reach a relocated copy, and dropping jinjava would remove the `jlama`
provider. Remove the entry when a jinjava release shades jsoup >= 1.23.2.

**Node-local dead letters kept in id order.** `ClusterConversationCoordinatorTest.localDeadLettersPage` failed once in CI on this branch, and the cause was real. `AbstractQueuedConversationCoordinator.recordLocalDeadLetter` numbered an entry before taking the ring's lock. Two conversations failing at once could therefore add `local-3` before `local-2`, and the cluster coordinator's paging, which skips every local entry numbered at or below the cursor, then dropped `local-2` from the next page. The number is now taken inside the lock. `InMemoryConversationCoordinatorTest.localDeadLettersAreHeldInIdOrderUnderConcurrentFailures` forces that interleaving and fails without the fix.
