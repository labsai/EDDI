# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [October 2026](changelog/2026-10.md) | 208 | 938 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1216 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 📚 feat(docs-site): redirects for every URL GitBook ever served, CI, and the cutover kit (2026-10-27)

**Repo:** EDDI (`feat/docs-site-astro`)

### What changed and why

Phases 2–4 of [`planning/docs-site-migration-plan.md`](../planning/docs-site-migration-plan.md). After this, moving docs.labs.ai off GitBook is a Vercel project and a DNS record — the checklist is phase 5 of the plan.

- **Fidelity (phase 2), by script over all 94 pages.** Source against build: the same number of tables and code blocks, and no Markdown leaking into rendered text. GitBook against build: 98.4% mean word coverage, every gap GitBook's page chrome or text that changed on `main` after the branch was cut.
- **Every old URL keeps working (phase 3).** [`docs-site/legacy-urls.txt`](../docs-site/legacy-urls.txt) lists every URL docs.labs.ai has served — today's sitemap, the Wayback Machine's older flat and chatbot-era URLs, the links in this repo and the website. [`scripts/redirects.mjs`](../docs-site/scripts/redirects.mjs) generates `vercel.json`'s redirects from it: docs.labs.ai → the same path on docs.eddi.technology, then each legacy path in its plain, trailing-slash and `.md` forms, then a per-section fallback. Pages whose slug contains a dot (`upgrading-from-6.4`) get an explicit slash redirect, because `trailingSlash` takes `.4` for a file extension and would 404 them — found by the new checker.
- **A guard over docs links in the repository.** Every docs URL written in the backend, the UIs, README, CONTRIBUTING, AGENTS, the plugin and `.github/` must land on a page, anchor included. Released images print those links forever; three of them 404 on GitBook today.
- **[`scripts/check-live.mjs`](../docs-site/scripts/check-live.mjs)** checks a deployment over HTTP — 693 URLs followed hop by hop. Run locally against a stand-in for Vercel's serving of `vercel.json`, it found the dotted-slug 404s and a generator bug that rewrote an external `…/CONTRIBUTING.md` target to `…/CONTRIBUTING/`; both are fixed and covered by tests.
- **Headers:** the website's security headers, with a CSP written for this site (`'wasm-unsafe-eval'` for the search index, shields.io and quay.io images). Search, Mermaid and badges were checked in a browser under it with no violations.
- **CI (phase 4):** [`docs-site.yml`](../.github/workflows/docs-site.yml) — check *Docs Site* — runs the tests on every PR (they hold the repo-wide link guard) and the site build when docs changed. Dependabot watches `/docs-site`, because CI's Trivy scan reads its lockfile and an advisory there would fail every backend PR. `ci.yml`'s `backend` filter gains `docs-site/**/*.md`, and `BuildQualityGatesTest` holds `docs-site/.nvmrc` to the pom's Node.
- **Branding matched to the website and the Manager, value for value.** Tokens copied from the website's `global.css`; the header bar, its right side (Website, Get Started, GitHub) and the sun/moon toggle from its `Header.astro`, replacing Starlight's theme `<select>`; prose from its `GuidePage.astro`; code blocks in Astro's default `github-dark` theme with the same geometry; the website's favicons; the Manager's sidebar active/hover treatment and scrollbars. Checked against the live site by computed style (header, logo, CTA, nav link, headings, body text, links, code, tables: identical) and by screenshot in both themes and at phone width. Two deliberate differences: light-mode links use the website's own light accent token (accent-700) rather than accent-400, which is ~1.9:1 on its light background; and the docs render Outfit and JetBrains Mono for real, which the live website currently does not (it asks for `Outfit`, but `@fontsource-variable/outfit` registers `Outfit Variable`, so it falls back to the system font; inline code falls back to the browser's generic monospace).
- **No `sharp`.** Dependency Review failed the PR on the ten `@img/sharp-libvips-*` packages (LGPL-3.0), which `sharp` — Astro's optional default image optimiser — pulls in and the repository's licence policy denies. The site has no raster images, so it uses Astro's pass-through image service and `package.json` overrides `sharp` with a local stub (`docs-site/stubs/sharp`); the policy is unchanged. [`licenses.test.mjs`](../docs-site/scripts/licenses.test.mjs) checks the lockfile against the deny-list read from `dependency-review.yml`, so this fails `npm test` next time rather than a PR check.
- AGENTS.md (ecosystem row: the website is Astro + Tailwind on Vercel at eddi.technology), CONTRIBUTING (how to add a page), the ship-pr skill (the new check) and the monorepo plan now describe the docs as they are built.

### Verification

`npm test` in `docs-site/`: 52 tests; the new guards were mutation-checked. `npm run build` clean; `check-live` 693/693 against the local stand-in. The Java guards touched here (`BuildQualityGatesTest`, `DocumentationLinksTest`, `DocumentationAccuracyTest`, `DocumentedRestPathsTest`, the changelog tests) pass.

---

## 🐛 fix(email): HITL notification review findings after the retarget to main (2026-10-27)

**Repo:** EDDI (`feat/email-hitl-notifications`)

### What changed and why

Once #1140 merged and #1141 was retargeted to `main`, the review bots saw the whole slice and raised these:

- **One recipient's failure no longer drops the rest.** `HitlEmailNotifier.notifyPause` handles each recipient on its own: a store error while counting, inserting or attempting one recipient is logged under that recipient's pseudonym and the loop moves on. The sweep only retries rows that exist, so a recipient skipped this way would never have been mailed for that pause.
- **A send failure that is not a `MailSendException` is an attempt.** A `RuntimeException` from `SystemMailer.send` (a bad address, a mailer bean that could not be built) used to escape `attempt`, leaving the row PENDING and reclaimed after every lease without ever reaching `max-attempts`. It is now rescheduled with backoff and FAILED at the limit, like a transient SMTP failure.
- **The audit entry names the recipient by keyed pseudonym.** It carried an unkeyed SHA-256 of the address beside the recipient's domain, which anyone reading the ledger or the logs could confirm by hashing candidate addresses. It is now `recipientPseudonym`, from `GdprPseudonymizer` (the audit ledger's keyed pseudonym, or the per-deployment salt), the same token GDPR erasure writes for that identifier; the rate-limit WARN uses it too.
- **A `From` with a valid mailbox beside an unparseable entry has no sender.** `MimeMessageParser.parseFrom` now records `MULTIPLE_FROM` for it, as `AddressNormalizer.parseSingle` already did, so DKIM/DMARC never authenticates a domain the header did not name unambiguously.
- **Manager:** a switched-off HITL section no longer blocks the save over hidden recipients, and the section resets per agent and version (`key` on `HitlConfigSection`).
- **`SystemMailer`** states `mail.smtp.ssl.checkserveridentity=true` where the session is built (CodeQL `java/insecure-smtp-ssl`); `SafeSocketConnector.angusProperties` already set it under the protocol's prefix.
- **Dashboard:** the email panels collided with main's Conversation Ratings panels on ids 264 and 265; they are now 267 and 268.

Files: [`HitlEmailNotifier.java`](../src/main/java/ai/labs/eddi/integrations/email/notify/HitlEmailNotifier.java), [`MimeMessageParser.java`](../src/main/java/ai/labs/eddi/engine/mail/MimeMessageParser.java), [`SystemMailer.java`](../src/main/java/ai/labs/eddi/engine/mail/SystemMailer.java), [`agent-config-sections.tsx`](../ui/manager/src/components/editors/agent-config-sections.tsx), [`agent-detail.tsx`](../ui/manager/src/pages/agent-detail.tsx), [`eddi-full-metrics-dashboard.json`](monitoring/eddi-full-metrics-dashboard.json), with tests for each; each new test fails with its fix reverted.

---

## fix(alerts): a per-event send claim closes the erasure-vs-delivery window (2026-10-26)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138)

### What changed and why

A delivery could read an event, an erasure redact it and finish, and the delivery then send what it had read. Now **no send starts with erased content after an erasure has completed**.

- **Send claim.** `IAlertEventStore.claimSend(eventId, token, now, until)` is an atomic conditional write that succeeds only when no claim is live (none, or an expired one); `releaseSend(eventId, token)` clears it only for its own token. Both stores keep the claim in internal fields (`sendClaimToken` / `sendClaimUntil`; `send_claim_token` / `send_claim_until`) that no read maps into an event. `AlertDeliveryService` — the one path for first deliveries and retries — claims for `SEND_CLAIM_LEASE` (a fixed 30 s, three times the default 10 s webhook timeout; not tied to a configured `eddi.alerts.webhook-timeout`), reads the event, checks that more than the send timeout is left of the lease, sends, and releases in a `finally`. A claim it cannot take, or a lease too short to finish in, leaves the delivery pending, due again after one lease; the worker never blocks.
- **Erasure waits for live claims.** The redactions erasure uses (`redactOutputValues`, `redactRatingComments`, and the engine's `redactRatingComment`) take `now` and skip an event whose claim is live. [`AlertErasureRedactor`](../src/main/java/ai/labs/eddi/engine/alerts/AlertErasureRedactor.java) — used by `AlertGdprParticipant` and `ConversationRatingGdprParticipant` — repeats the redaction with a short pause until `countOutputValues` / `countRatingComments` report nothing left, so the erasure completes only after every send that had read the content has finished or its claim has expired.
- **The engine's own post-insert redactions cannot wait on themselves:** they run before the event's first delivery and before the maintenance pass may pick it up (its `nextAttemptAt` is a claim lease away), so no claim on it can exist yet (said in the code).
- [Alerts → Privacy](alerts.md#privacy) states the guarantee and its one limit: a send that started before the erasure completed cannot be recalled.

### Design decisions

- **Claim per send, not per event delivery:** an event with five targets would otherwise hold one claim across up to five webhook timeouts.
- **The erasure's wait is bounded** by ten leases (5 minutes). A claim always expires within one lease, so only a store that keeps failing the update — or deliveries re-claiming without pause, which the backoff rules out — reaches the bound; the step is then reported failed instead of hanging the erasure. This is the one place the implementation is stricter than "keep going until the claims expire".

### Tests

`AlertSendClaimTest` drives the races with latches, not sleeps: a send paused after its final read holds the erasure (asserted not complete after two redaction passes), the erasure completes once the send finishes, and a retry then sends the redacted event; erasure first, then delivery, sends the redacted event; a live claim makes the attempt skip, an expired one is taken over; a lease too short after the read releases and skips; the erasure's bound fails the step. Store contract (both ITs): conditional claim, token-checked release, expiry takeover, redaction skipping a claimed event until it is released or expired.

---

## 📚 feat(docs-site): build docs/ with Astro + Starlight, the replacement for GitBook (2026-10-26)

**Repo:** EDDI (`feat/docs-site-astro`)

### What changed and why

docs.labs.ai is GitBook, syncing `docs/` one way. The documentation moves to a site we build ourselves: Astro + Starlight, deployed on Vercel like the marketing site, at `docs.eddi.technology`, with docs.labs.ai redirecting there permanently. The plan, and the facts it rests on (the URLs GitBook has served over the years, the website's hosting, what GitBook provides besides pages), is [`planning/docs-site-migration-plan.md`](../planning/docs-site-migration-plan.md). This is its phase 1: the site builds locally. Nothing is deployed and docs.labs.ai is unchanged.

- **`docs/` is untouched as a format.** It stays plain GitHub Markdown, because GitHub renders it, `McpDocResources` serves it raw to MCP clients and `DocumentationLinksTest` checks it. [`docs-site/scripts/sync-docs.mjs`](../docs-site/scripts/sync-docs.mjs) reads it on every dev/build run and writes the Starlight copy. `docs/SUMMARY.md` still decides what is published and is now the sidebar too.
- **Flat URLs from file names** — `docs/secrets-vault.md` → `/secrets-vault/`. GitBook's URLs carried the SUMMARY section (`/security-and-compliance/secrets-vault`), so moving a page between sections changed its address; and the flat links the backend and the Manager already print (`VaultStartupBanner`, `ComplianceStartupChecks`, the coordinator page) 404 on GitBook today. They resolve on the new site.
- **Links are rewritten, and checked.** A link to a published page becomes its route; a link to anything else in the repository becomes a GitHub link. The sync fails on a SUMMARY entry without a file, a link to nothing, two pages that would share a URL, and an `#anchor` that does not exist. Anchors are checked against the source because `starlight-links-validator` 0.27 cannot check them under Astro 7: Astro's satteri processor runs plugins before it assigns heading ids, so the plugin sees none and reports every anchor as broken. The source-side check computes the ids with `github-slugger`, the same way Astro does, and matches all 5,235 heading ids of the built site, none missing and none extra. The plugin still checks every page link.
- **GitBook's machine-readable surface is kept:** every page also exists as `/<slug>.md` (the website links to GitBook's `.md` URLs), and `/llms.txt` lists every page, with `/llms-full.txt` and `/llms-small.txt` (no changelog) beside it.
- **Everything generated lives in `docs-site/.generated/`**, which is gitignored. `DocumentationLinksTest`, `DocumentationAccuracyTest` and `DocumentedRestPathsTest` skip it by name, so a local site build cannot feed them a second copy of `docs/` with site-relative links.
- The anchor check found one real broken link: two references in [`changelog/2026-08.md`](changelog/2026-08.md) pointed at a heading that had been rotated into the July archive. They now link there.

### Verification

- `npm test` in `docs-site/`: 32 tests, including a full sync over the real `docs/`. Each guard was mutation-checked: disabling the anchor check, blockquote headings, setext headings, entity decoding, the intra-word underscore rule, fence-length and code-span handling, directory READMEs or the slug-collision check each fails a test.
- `npm run build`: 94 pages, link validator clean. Seen in a browser — search, Mermaid, dark and light theme, phone width, `.md` endpoints, the no-slash URLs the backend uses — with no console errors.

### Next

Phase 2 of the plan: a page-by-page comparison against GitBook. Then phase 3, the redirect map from every URL GitBook has served, and phase 4, the CI workflow and the Vercel project.

---

## 🔐 fix(auth): MCP logins last 30 days, and Keycloak behind TLS advertises https token endpoints (2026-10-26)

**Repo:** EDDI (`fix/mcp-long-lived-sessions`)

### What changed and why

Connecting Claude Code to a TLS-proxied EDDI deployment surfaced two gaps, both
configuration only — no Java change.

**1. An MCP login died after 30 idle minutes.** An MCP client keeps a refresh
token, but it was tied to the realm's SSO session (Keycloak default: 30 minutes
idle, 10 hours max), so the user re-ran the browser flow after every break.
`eddi-mcp` now issues **offline tokens** (30 days idle, renewed on use, revocable
per user). In all three realm copies (`keycloak/`, `helm/eddi/files/`,
`k8s/overlays/auth/`): `offline_access` is a Default client scope of `eddi-mcp`,
the role is in its scope mapping, `default-roles-eddi` includes it, and each seed
user lists it. The scope, the role and the scope's role mapping are now defined in
the file itself: the realm supplies its own `clientScopes`, and
`everyReferencedClientScopeIsDefined` rightly refuses a reference to a scope the
file does not define, even though Keycloak 26.8 happens to create this one anyway.
`default-roles-eddi` is stated twice in the file (`defaultRole` and the
`roles.realm` entry) and the roles-list entry is the one an import keeps — with the
role added only under `defaultRole`, a newly created user was refused, so both
carry it and the test checks both. `eddi-frontend` is unchanged — no 30-day tokens
in browser storage.

Verified against Keycloak 26.8.0 with the edited realm imported: all seed users
and a newly created user receive `typ=Offline` refresh tokens (2 592 000 s) with
their roles and `aud=eddi-backend`, and the refresh grant works. The same test
disproved both recipes `docs/mcp-server.md` previously gave:

- `default-roles-eddi` did **not** grant `offline_access` (the realm defines its
  composites explicitly) — without the role: *Offline tokens not allowed for the
  user or client*.
- Advertising `offline_access` in `quarkus.oidc.resource-metadata.scopes` makes
  Keycloak fail the **whole login** with `invalid_scope` on any realm whose client
  lacks the scope — i.e. every existing deployment. Left at `openid`.

**2. Keycloak behind a TLS proxy advertised `http://` token endpoints.** With
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, Keycloak derives the token, JWKS and
userinfo URLs from the request, which is plain http behind the proxy; the issuer
and authorization URLs come from `KC_HOSTNAME` and stayed https. Claude Code
refuses to send credentials to an http token endpoint, so login failed after the
browser step. Fix: `KC_PROXY_HEADERS=xforwarded`.
- `gcp/provision-vm.sh --https` now sets it, and its nginx Keycloak location
  now **sets** every forwarded header instead of passing client values through:
  `X-Forwarded-Host $host`, `X-Forwarded-Port 443`, `X-Forwarded-For
  $remote_addr` (was appended). Once Keycloak trusts these headers, a
  client-supplied `X-Forwarded-Host` would choose the host in that caller's
  discovery response (review finding, CWE-346).
- Helm: new `keycloak.proxyHeaders` — empty means `xforwarded` when
  `eddi.oidc.publicUrl` is https, nothing otherwise; `none` opts out. Rendered and
  checked for all four cases. Chart `2.11.0` → `2.12.0`.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`,
  `k8s/overlays/auth/eddi-realm.json` — offline-token wiring.
- `helm/eddi/values.yaml`, `helm/eddi/templates/keycloak.yaml`,
  `helm/eddi/Chart.yaml` — `keycloak.proxyHeaders`.
- `gcp/provision-vm.sh` — `KC_PROXY_HEADERS` in the HTTPS override.
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java` — the
  `eddi-mcp` scope-mapping assertion allows `offline_access`; new
  `mcpClientIssuesOfflineTokens` pins the three conditions in every realm copy and
  that `eddi-frontend` stays without it.
- `ui/manager/scripts/make-test-realm.mjs`, `ui/manager/e2e/auth/auth-helpers.ts`
  — the Auth E2E fixtures pin each seed user's exact roles; they now include
  `offline_access`. `ui/manager/e2e/auth/mcp-oauth.spec.ts` — the real
  authorization-code + PKCE + consent flow against `eddi-mcp` now asserts the
  refresh token is `typ: Offline`, the one path the container check could not drive.
- `docs/mcp-server.md` — corrected offline-token section, upgrade steps for an
  existing realm, proxy-header deployment note. `docs/kubernetes.md` — settings row.

### Upgrade note

`--import-realm` does not touch an existing realm. Existing deployments keep
working with ordinary 30-minute MCP logins; for long-lived ones they add the three
pieces by hand (steps in `docs/mcp-server.md`, "Upgrading an existing realm"),
the Default scope **last** — with the scope in place but the role missing from
the user or the client's scope mapping, Keycloak refuses the login with *Offline
tokens not allowed* rather than falling back to an ordinary token. Revoke a
single offline token under *Users* → the user → *Consents*. The nginx Keycloak
location also clears `X-Forwarded-Prefix` (review finding).
The `quarkus.oidc.roles.role-claim-path=realm_access/roles` fix for "requires
role" on every tool is already in the image since 6.6.0; deployments on older
images should pin it as `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`.

---

## 🐛 fix(ci): the Playwright install no longer waits on the Ubuntu apt mirror (2026-10-26)

**Repo:** EDDI (`fix/playwright-install-slow-apt`)

### What changed and why

`main` went red on the `fast-uri` bump (`daecb3513`) with no test failing. `UI Manager E2E (MSW)` and `Backend E2E (postgres)` both hit the 10-minute limit of `Install Playwright browsers`, and the re-run failed the same way. `npx playwright install --with-deps chromium` always runs apt-get. On `ubuntu-latest` that meant about 26 MB of fonts (CJK, unifont, xfonts) plus a few library upgrades from `azure.archive.ubuntu.com`, which was serving 25–50 KB/s that afternoon. None of it is needed: the suites take screenshots only on failure and compare none, and the runner image already carries Chromium's shared libraries.

The three steps that installed Playwright (`UI Manager E2E (MSW)`, `Backend E2E`, `Auth E2E (Keycloak)`) now run [`ui/manager/scripts/install-playwright-chromium.mjs`](../ui/manager/scripts/install-playwright-chromium.mjs). It:

- downloads the browser from Playwright's CDN (no apt);
- launches it once, and if that works, skips apt altogether;
- otherwise prints a `::warning::`, runs `npx playwright install-deps chromium`, and fails the step if Chromium still does not launch.

So a runner image that drops a library still works, it just pays the apt cost again, and the warning says so.

### Verified

Both paths ran in Docker against `@playwright/test` 1.64.1:
- `node:24-bookworm` (no Chromium libraries): the probe reported a missing shared library, apt installed the dependencies, the second launch succeeded, exit 0.
- `mcr.microsoft.com/playwright:v1.64.1-noble`: launched first time, apt skipped, exit 0.

The `ubuntu-latest` path itself is verified only by this PR's own CI run.

---

## 🔒 fix(security): schedule fire authority, maxCostPerFire, group approver scope, currentversion VIEW (2026-10-26)

**Repo:** EDDI (`fix/schedule-group-authz`)

Six findings of the repository security scan (revision `daecb3513`) on scheduling and group-conversation authorization.

### What changed and why

- **Schedule creator re-check used historical teams (#4).** `ScheduleFireExecutor.creatorUseCheck` re-checked USE with the create-time `creatorTeams` snapshot, so a creator removed from a team kept firing a schedule on an agent shared only with that team. The snapshot is now narrowed by the user directory: when the creator signed in after the schedule was created, a team their latest token no longer lists stops counting (`currentCreatorTeams`). Only ever narrowing; with no newer directory entry the snapshot stands. **Not fixed:** `creatorAdmin` — the directory records teams, not roles, and EDDI has no server-side role source outside a request, so a creator who loses `eddi-admin` keeps an admin-created schedule firing. Recording roles in the directory (both stores) would close it.
- **`maxCostPerFire` capped nothing (#26).** The field was accepted, stored and shown but never reached the turn. The fire now carries it in the input context (`scheduleMaxCostPerFire`), and `LlmSpendGate.effectiveCeiling` — used by the pre-call spend gate and between tool-loop rounds — tightens the agent's `maxCostPerConversationUsd` to "every earlier step's `audit:cost` plus the allowance", so a `persistent` schedule is capped per fire. `0` or below stays unlimited (PostgreSQL stores `0` as NULL).
- **`pendingHumanPrompt` went to every approver (#8).** The approval-status summary (REST and MCP `get_group_approval_status`) returned a HUMAN member's rendered prompt — discussion content — to any `eddi-approver`. It now goes only to the pending member, the owner, admins, and an approver while the conversation awaits approval (`HitlAccessGuard.mayReadPendingHumanPrompt`), the same window the full view grants.
- **`approve/stream` streamed the resumed discussion to approvers (#27).** An approver-only caller now gets a listener that forwards `hitl_resume` (or, on a rejection, `group_complete` without the synthesized answer), errors and cancellation, then closes; the owner and admins keep the full stream (`HitlAccessGuard.mayReadResumedDiscussion`). Same boundary as the agent resume's "a reply is a read".
  The approver's `hitl_resume` is held until `resumeDiscussion` returns: the coordinator announces the resume before it submits the resumed run, and a submit failure restores the pause and throws, so sending it at once told the approver their decision was applied while the discussion stayed paused, and closed the stream before the endpoint's `group_error` could reach it (`ApproverStreamingListener.resumeEnqueued`).
- **A tool-call resume skipped the cost ceiling.** `LlmTask.executeResume` never asked the spend gate, and the tool loop checks its ceiling only after the model has answered, so a conversation (or a scheduled fire under `maxCostPerFire`) spent while it waited for approval still ran the approved calls and its first resumed model request. The resume now runs the same preflight before the approved calls (refused → the fallback answer, `llm:spend_refused:<taskId>`, the batch consumed), and the resumed loop checks before its first request too, counting what the approved calls charged: `runToolCallLoop` takes the tool-cost baseline from its caller, taken before those calls, instead of snapshotting it at loop start.
  What the turn spent *before* the pause was invisible to both checks: the loop's tokens and tool cost so far died with the pause signal, so a fire that had used $0.02 of a $0.025 allowance resumed as if it had spent nothing. The pause now carries that spend (`ToolApprovalRequiredException.addSpend`: the loop's rounds at the task's prices plus its tool-cost delta, and an enclosing cascade's earlier steps), and `LlmTask.execute` adds it to the step's `audit:cost` and `audit:token_usage` as the pause passes — the step the pause snapshot persists and the pause's settle meters. The resume reads it through `audit:cost` in its preflight, its loop ceiling and its total, so it counts once; this also closes the documented gap where a resumed turn's ledger entry under-reported its pre-pause segment. Docs: [`langchain.md`](langchain.md#per-conversation-llm-cost-ceiling).
- **Follow-up did not re-check group USE (#37).** `POST …/followup` (and MCP `followup_with_member`) checked transcript ownership only; like a continuation, it now also requires current USE on the group.
- **`/{id}/currentversion` had no per-resource check (#6).** `IRestVersionInfo`'s two endpoints now call `requireCurrentVersionAccess(id)` first; all sixteen stores (and `RestVersionInfo`) implement it as VIEW via `RestVersionInfo.requireViewAccess`. The default refuses, so a new store that forgets fails closed. `getCurrentResourceId` stays the unguarded resolver in-process engine code uses.

### Files

[`ScheduleFireExecutor.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java), [`ScheduleConfiguration.java`](../src/main/java/ai/labs/eddi/engine/schedule/model/ScheduleConfiguration.java), [`LlmSpendGate.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmSpendGate.java), [`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`ToolLoopResumer.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java), [`ToolApprovalRequiredException.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalRequiredException.java), [`CascadingModelExecutor.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`HitlAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java), [`RestGroupConversation.java`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java), [`McpHitlTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java), [`McpGroupTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpGroupTools.java), [`IRestVersionInfo.java`](../src/main/java/ai/labs/eddi/configs/IRestVersionInfo.java) and the sixteen `Rest*Store` classes. Docs: [`scheduling.md`](scheduling.md), [`hitl.md`](hitl.md), [`langchain.md`](langchain.md), [`group-conversations.md`](group-conversations.md).

### Design decisions

- `maxCostPerFire` rides the existing priced-spend ceiling rather than a new tool-only budget: it then covers model tokens as well as tools, and every node that runs the turn enforces it because the value travels in the input. A client that sets the context key itself can only lower its own ceiling.
- The approver stream closes at the resume instead of filtering event by event: everything after the resume is outside the approval window, and a closed stream cannot start leaking when a new event type is added.
- The pre-pause spend is recorded on the step at the pause, not carried on the persisted `PendingToolCallBatch` for the resume to add: the step already survives into the pause snapshot and is what `TurnCostMeter` settles, so the spend is metered even if the pause is never resumed, and a figure on the batch would be one more place a cost could be applied twice.

---

## 🔒 fix(security): close the DNS-rebinding gap between the SSRF check and the connection (2026-10-26)

**Repo:** EDDI (`fix/ssrf-dns-pinning`)

### What changed and why

A repository security scan flagged three paths where the SSRF check resolves a host and the HTTP client then resolves it again to connect: `OutboundHttp` (JDK client), the Vert.x httpcalls client, and cached MCP transports. Both lookups normally hit the JVM's positive DNS cache and agree; a rebinding name aims for the moment that cache entry expires between them. This was recorded as an accepted risk in `docs/architecture.md`. None of the clients can be handed the validated addresses (the JDK `HttpClient` has no resolver hook, and an IP-literal URL breaks SNI and certificate checks), so each path now closes the gap with what it can see:

- **httpcalls (Vert.x):** a web-client interceptor checks the address the connection actually reached in the `SEND_REQUEST` phase, before a byte is written, on every request, pooled connection and redirect hop. A metadata or link-local peer is refused always; with `eddi.security.ssrf-protection.enabled=true` a private peer is refused unless the host is allow-listed. This is a real pin of the decision to the connection.
- **`OutboundHttp`:** each hop's SSRF check runs again once the response headers are in. The connection's own lookup is what the cache now holds, so a target that turned private (or into the metadata service) has its response discarded and closed before it reaches the caller or a redirect. A startup warning names `networkaddress.cache.ttl=0`, which would defeat it.
- **MCP:** the server URL is validated on a tool-cache hit too, not only on a fresh discovery, and again inside the transport's per-request header hook, so a cached transport never sends to a URL that no longer passes.

What remains (written up in [security.md → DNS rebinding](security.md#dns-rebinding)): on the `OutboundHttp` path the request itself has already been sent when the second check refuses it, so this blocks reading the response, not a blind request; an exchange slower than the cache lifetime defeats the second look; MCP keeps the pre-connect window. A full pin of the JDK path needs a resolver hook the JDK does not offer, or one `HttpClient` per pinned host with explicit SNI, which is a larger change.

**Files:** [`HttpClientModule.java`](../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java), [`OutboundHttp.java`](../src/main/java/ai/labs/eddi/engine/httpclient/outbound/OutboundHttp.java), [`McpToolProviderManager.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManager.java), [`UrlValidationUtils.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java), [`security.md`](security.md), [`architecture.md`](architecture.md). Tests: `HttpClientModuleConnectedPeerTest`, `OutboundHttpTest$Rebinding`, `McpTransportRevalidationTest`, `McpToolProviderManagerDiscoveryTest#cacheHitIsRevalidated`.

---

## 🔒 fix(llm): tool results redacted before the model, bounded selector extraction (2026-10-26)

**Repo:** EDDI (`fix/tool-result-redaction`)

Two findings from a repository security scan of `daecb3513`.

### What changed and why

- **Tool results are redacted before they reach the model.** Only the
  display trace was filtered: an administrator's config-read tool (the Platform
  Operator) showed `<REDACTED>` while the hosted model provider received the
  plaintext key. `ToolLoopRunner.executeToolCall` now runs
  `SecretRedactionFilter.redact` on the result before truncation (the
  `summarize` strategy sends it to a model too) and before the guardrail
  envelope; the raw result stays internal (LAZY activation, repeat guard).
  Trade-off, documented: a key-named value such as `nextPageToken` reaches the
  model as `<REDACTED>`.
- **Selector extraction no longer multiplies nested text.**
  `WebScraperTool.extractWithSelector` copied each match's whole descendant
  text, so nested matching ancestors repeated the same text up to 20 times. An
  element inside an already listed one is skipped, and text is gathered by a
  bounded walk (`boundedText`) against one 20,000-character output budget (5,000
  per element), so nothing past the budget is materialized.

### Design decisions

- **The webhook replay findings of the same scan are not in this change.** Open
  PR #1126 already moves the webhook dedup cache to a cluster-shared bucket and
  refuses instead of evicting; the longer retention for body-signed schemes
  (`github`, `generic-hmac`) follows once it has merged, so the two do not
  conflict in `WebhookEventHandler` and `CacheFactory`.

**Files:** [`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`WebScraperTool.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperTool.java),
[`security.md`](security.md)

---

## 🔒 fix(secrets): linear-time reference scanning, a new secret's grant is checked against agents already referencing it, rotation retires legacy checksums (2026-10-26)

**Repo:** EDDI (`fix/vault-reference-scanning`)

Follow-up to a repository security scan of the vault and connection code.

### What changed and why

- **Reference scanning is linear.** `find()` over the `${vault:…}`, `${connection:…}`, `${vars:…}` and tenant-reference regexes retried at every opener, and when no closing brace followed, each attempt scanned the rest of the text. A global variable value or a parameter with thousands of unterminated openers therefore cost time quadratic in its length, on every variable write, every deployment grant check and every resolved parameter. The new [`ReferenceScanner`](../src/main/java/ai/labs/eddi/secrets/model/ReferenceScanner.java) gives the regex's answers match for match (same start, end, tenant and name, nested openers included) but remembers its searches for the next `}` and `/`, so each character is examined a constant number of times. `SecretResolver`, `VaultGrantChecker`, `SecretReference.parse` and `ConnectionReference` (`contains`, `requireSole`, `parse`) use it. `SecretReference.VAULT_PATTERN` and `ConnectionReference.CONNECTION_PATTERN` stay as the documented grammar.
- **A secret's grant is checked when it is created, against the agents already referencing it.** [`VaultGrantChecker`](../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java) lets a deployment through when a referenced secret does not exist yet — agent sync promotes a workflow to an instance that does not hold the source's secret and then deploys it, and `AgentSyncIT` covers that flow. The resolver checks no grants, so a secret created later and restricted to another agent used to resolve for every agent already deployed with a reference to it, until its next deployment. `PUT /secretstore/secrets/{tenantId}/{keyName}` and the space-secret endpoint now answer **409** under `enforce` to a create whose `allowedAgents` leaves out such an agent, naming it ([`VaultGrantImpactAnalyzer.refusalToCreate`](../src/main/java/ai/labs/eddi/secrets/VaultGrantImpactAnalyzer.java), reusing the grant-change impact scan); `warn` logs and creates, `off` does not look. A first version of this branch made the absent secret a deploy-time violation instead, which broke the agent-sync flow.
- **The create-time scan covers the cluster, and refuses when it could not look everywhere.** The scan read only `IAgentFactory`, a per-node registry, while the vault is shared by every node, so an agent deployed on another node was invisible to a create handled here. It now also reads the deployment records in the shared database (`IDeploymentStore.readDeploymentInfos(deployed)`), deduplicated against the local registry, which alone sees a deployment still `IN_PROGRESS` (now counted) or one made with `autoDeploy=false`. And `refusalToCreate` used to ignore the scan's `complete` flag, so under `enforce` an agent whose configuration could not be read, or a listing that failed, let the create through on an empty list. It now refuses with its own 409 naming the unread agents (`GrantImpact.unverifiedAgents`). This is the opposite of the deploy gate on purpose: a refused deployment is an outage, a refused create is a retry, and naming the agent on the grant or granting `*` needs no scan. The grant-change response's `agentsLosingAccessScope` reads `cluster` instead of `this-node`. Left open: a deployment recorded on another node a moment after the scan read the records.
- **The checksum key cannot be stranded under a retired KEK.** `loadOrCreateChecksumKey` checked the KEK before inserting the key, but a node stalled between that check and the insert past `rotateKek`'s last read of the key would persist it wrapped under the retired KEK, and every node restarted with the new master key would then fail on its next store. The race predates this branch (first `store`), and the legacy-checksum upgrade in `rotateDek` made it reachable a second way. The insert is now followed by a second check, as for a DEK (`takeBackIfKekRetired`): when the KEK moved, the key is deleted only while it is still the value this node wrote (`ISecretPersistence.deleteMetaValueIfEquals`, atomic on Mongo and Postgres), so a key the rotation already re-wrapped is kept.
- **DEK rotation upgrades legacy checksums.** A row written before the keyed checksum still held a bare SHA-256 of the plaintext, which anyone with the row or a backup can test guesses against offline. `VaultSecretProvider.rotateDek` has the authenticated plaintext in hand, so it now writes the keyed, tenant-bound checksum in the same guarded re-seal. `updateSecretSealing` (Mongo and Postgres) persists the checksum with the ciphertext.

Docs: [`secrets-vault.md`](secrets-vault.md) ("What counts as granted", and the checksum note under the REST API).

### Tests

- `ReferenceScannerTest`: parity with the regexes on hand-picked and fuzzed input, `matchesWhole`, `replaceAll`, and timing guards on many unterminated openers for each caller.
- `VaultGrantCheckerTest.absentSecretIsNotAViolation`; `VaultGrantImpactAnalyzerTest` (create-time refusal, modes, an unreadable agent or a failed listing refusing under `enforce`, agents found only in the deployment records, one agent in both sources checked once, `IN_PROGRESS` counted); `RestSecretStoreGrantOnCreateTest` (409 and no write).
- `MongoSecretPersistenceTest` and `PostgresSecretPersistenceTest` (Testcontainers): `deleteMetaValueIfEquals` keeps a value that differs from the expected one and removes only the exact one.
- `VaultKeySafetyTest.StaleReplicaRace`: a checksum key inserted after a KEK rotation, via `rotateDek` and via `store`, is taken back and a node on the new master key can store.
- `VaultKeySafetyTest.rotationRetiresLegacyChecksums`.
- `PostgresSecretPersistenceUnitTest` follows the new parameter order.

**Stale deployment rows.** The deployment sweep now checks for a missing Agent config before skipping rows this node already tracks, so a shared row that the agent-delete cascade could not remove is retired (and the agent undeployed locally) instead of lingering (for rows this node already tracks the check runs at most once every 5 minutes per row, since the agent store has no read cache) and making the grant analyzer answer UNKNOWN for it.

---

## feat(hitl): a person's note on a rejected tool call asks the model to revise (2026-10-26)

**Repo:** EDDI (`feat/hitl-note-asks-for-changes`)

### What changed and why

An approval card lets a person send a note instead of approving ("use a shorter title"). Until now a rejected call always told the model "the reviewer declined this action, do not retry, offer alternatives", so a note that asked for a change produced an explanation instead of a corrected proposal. Two small changes in [`ToolLoopResumer`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java):

- **The instruction depends on who decided.** When the note is non-blank and `decidedBy` does not start with `system:`, the rejection tool-result says the user did not approve the call and left a note: if the note asks for changes, call the tool again with exactly those changes and nothing else changed (the user will be asked to approve the new call); if it declines or asks for something else, do not call it again and reply briefly. With no note, or for an automated decision (`system:timeout`, `system:group`), the text is exactly what it was. Detection is the existing `system:` convention on `decidedBy`, never the note text, so the group-conversation auto-reject cannot make the model retry a call that can never be approved.
- **A per-call rejection falls back to the top-level note.** A call listed in `toolDecisions` with `REJECTED` and no note of its own (absent or blank) now uses `decision.note`, as an unlisted call already did. A call's own note still wins; approved calls are unaffected.

The re-proposed call is an ordinary new call: it is gated, pauses again with a new `pauseId` and needs its own approval, so an approved call is never silently changed. Each re-proposal counts toward `hitlConfig.toolApprovals.maxPausesPerTurn` (default 3, maximum 10); [`docs/hitl.md`](hitl.md) says to raise it where notes are expected to come back as revisions.

No REST shape, verdict type or default changes.

### Tests

`ToolLoopResumerNoteTest`: the envelope for a person's note, for no or blank note, and for `system:timeout` / `system:group` with a note; the per-call fallback through the real resume loop (own note kept, absent and blank fall back, unlisted unchanged); and one test that stores a paused conversation as a snapshot document, loads it back, resumes it `REJECTED` with a note and asserts the model's next request carries the new instruction and the note.

---

## 🐛 fix(llm): a `\'` inside a JSON reply no longer turns a complete answer into the fallback (2026-10-26)

**Repo:** EDDI (`fix/llm-json-repair-backslash-quote`)

### What changed and why

Gemini sometimes escapes an apostrophe inside a JSON string the way JavaScript and Python allow: `"Japan\'s ruling class"`. JSON has no `\'` escape, so `ModelOutputParser` reported `invalid JSON syntax` for the whole reply, and a `convertToObject` task served its fallback answer ("Sorry, I could not answer that just now"). In the case that showed it, the reply was complete and correct, about 1,300 characters long, and one apostrophe was the only defect.

[`ModelOutputParser`](../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java) now drops the backslash of `\'` inside JSON strings, after the fence strip and before parsing. This is the one exception to its "invalid JSON is never fixed" rule, and the class Javadoc says so. It is safe because `\'` is never valid JSON and can only mean `'`.

- The pass is string-aware and linear. It copies every other escape as the pair it is, so `\\'` (an escaped backslash, then a quote) stays valid JSON and `\"` does not end the string. Outside strings nothing changes.
- The whole reply is repaired once; when the object has to be extracted from prose, each candidate is repaired on its own, so an unmatched `"` in the text before the object cannot shift what counts as inside a string.
- A reply without `\'` is not copied.
- A repaired reply reports `repaired`, like a stripped fence.
- A reply that is still invalid keeps its raw text, and a second, real syntax error is not hidden.

### Tests

`ModelOutputParserTest`:
- accepted: the observed case, several `\'` in one object and inside an array, fenced, with prose around it, and with an unmatched quote in the prose before it;
- left alone: `\\'` stays valid and unrepaired, and an escaped double quote does not end the string;
- still rejected: prose containing `\'` is `not JSON` with the raw text kept, and `\'` plus a trailing comma is still `invalid JSON syntax`.

Mutation checks:
- With the repair switched off entirely, the acceptance cases that contain `\'` fail, and the `\\'` case passes either way, as it should.
- With only the per-candidate repair removed, the two extraction cases fail: prose around the object, and the unmatched quote before it.

---

## feat(alerts): masked webhook URLs, protected system schedule, OUTPUT_MATCH rules and e2e specs (2026-10-25)

**Repo:** EDDI (`feat/conversation-ratings`) — review fixes to PR 5a and PR 5b of [`planning/conversation-rating-plan.md`](../planning/conversation-rating-plan.md) (§0.2)

### What changed and why

**Webhook URLs are treated as secrets.** A webhook URL often carries its own token, so no read returns it any more, for administrators either: REST (list, single read, create/update responses), MCP and the operator see `https://host/***` plus `urlConfigured: true` ([`DeliveryTarget.masked()`](../src/main/java/ai/labs/eddi/engine/alerts/model/DeliveryTarget.java), applied in `AlertAccess.redact`). On update an omitted or masked URL keeps the stored one (`RestAlertRules.restoreUrls`, matched by position and host, else the only stored webhook on that host); a new value replaces it; a masked URL in a create is refused. A `${vault:…}` reference is accepted as the URL itself and resolved only at send time (`AlertSenders`); unresolvable, or not http(s), means no delivery — the rule the signing secret already followed. "Send test alert" for a saved rule restores the stored URL server-side and requires write access to that rule. The Manager editor shows the masked URL with a "kept unless you replace it" hint.

**The `alerts-maintenance` schedule cannot be changed through the schedule surfaces.** `RestScheduleStore` answers 409 `{"error":"SYSTEM_SCHEDULE"}` to update, delete, enable, disable, retry and dismiss of a schedule carrying the system `alertType`; MCP `delete_schedule` refuses with `errorCode: SYSTEM_SCHEDULE`. Self-healing on startup and rule save stays as the backstop. The Manager's schedule list marks it system-managed with no edit, toggle or delete.

**`OUTPUT_MATCH` (§0.2).** A rule can now fire on what a clean turn produced:

- [`OutputMatcher`](../src/main/java/ai/labs/eddi/engine/alerts/OutputMatcher.java) — paths `properties.<a.b>`, `output.json.<a.b.c>` (the agent's output parsed only when it starts with `{`/`[`, at most 64 KB, Jackson nesting capped) and `actions`; depth ≤ 8, segments `[A-Za-z0-9_-]`, no wildcards or indexes; ops `exists|eq|neq|in|gt|gte|lt|lte|contains`, comparisons type-strict (numbers by value, a string never equals a number). The value is the author's constant: no expression engine, no template evaluation of runtime data.
- Save-time validation (`AlertRuleValidator` → `OutputMatcher.problems`): path grammar, op/value fit, `in` list 1–50 scalars, constants ≤ 1000 characters, `actions` only with `contains`/`exists`, and no field of another source.
- [`OutputMatchHook`](../src/main/java/ai/labs/eddi/engine/alerts/OutputMatchHook.java) runs from `ConversationStepRunner` for a turn that completed cleanly (not failed, not timed out, not paused for a human). On the turn thread it does one `getIfPresent` on `AlertRuleCache.cachedOutputNeeds`; an agent without enabled `OUTPUT_MATCH` rules stops there. Otherwise it copies only what the rules read — the named top-level properties (detached copies), the output text, the action list — into a `TurnSnapshot` and hands it to the alert worker; the live memory never leaves the turn. A cache miss loads the needs on the worker and that one turn copies all three.
- `includeValue` is honoured only when the agent version has conversation review enabled (`ConversationReviewPolicy`); the value goes to `match.value`, cut to 280 characters. Otherwise the alert names path, operator and constant only.
- Manager: condition builder ([`output-match-builder.tsx`](../ui/manager/src/components/alerts/output-match-builder.tsx)) — path source dropdown, path input with inline validation, value type, operator filtered by type, value, and an includeValue toggle explaining the review requirement; `pathProblem`/`outputMatchProblems` in [`alert-rules.ts`](../ui/manager/src/lib/alert-rules.ts) mirror the server. 30 keys in all 11 locales; MSW fixture masks its webhook.
- Operator prompt: masked URLs, the system schedule and `OUTPUT_MATCH`; revision 38 → 40.
- Docs: [Alerts](alerts.md#matching-a-turns-result) with the three §0.2 examples, masking, the system schedule.

**E2E specs.** `ui/manager/e2e/alerts.spec.ts` (MSW tier: inbox acknowledge, create rule, output-match builder, badge) and `e2e/fullstack/ratings-feedback.fullstack.spec.ts` (Chat UI rating → Feedback "Needs attention" + badge → mark reviewed → count back). Not run locally — Playwright browsers were not downloaded on purpose; CI runs them.

### Design decisions

- **A masked URL is restored by position and host, never by position alone.** A reordered target list still gets the right URL back when its host is unique among the stored webhooks; when it is not and the position does not match, nothing is restored and the masked URL is refused at validation rather than guessed.
- **Only top-level property names are copied**, even for a nested path: the snapshot copies the property's value detached (`AlertJson`), so a later change to live memory cannot reach the worker.
- **A cache miss copies everything once** instead of loading rules on the turn thread: the turn never waits on the store, and the first turn after a restart is not lost.

### Tests

- **Review fixes:** `RestAlertRulesTest.WebhookUrls` (list, single read, create/update response, restore by position and host, masked create refused, test send restores and needs write access), `AlertSendersTest` (vault URL resolved at send, unresolved → no delivery, labels), `RestScheduleStoreExpandedTest` and `McpAdminToolsBranchCoverageTest` (system schedule refused). Mutation check: 9 of 9 mutants killed (redaction, create and update responses, `masked()`, the label, `restoreUrls`, unresolved vault URL, the REST and the MCP system-schedule guards).
- **OUTPUT_MATCH:** `OutputMatcherTest` (paths, JSON-only parsing, size and nesting caps, type-strict operators, grammar and op/value validation), `OutputMatchHookTest` (an agent without rules costs one cache lookup and touches nothing else — `verifyNoMoreInteractions` on memory and cache, nothing queued; only needed values copied, detached; cache miss copies everything; `includeValue` needs review; 280-character cut), `AlertRuleValidatorTest`, `ConversationStepRunnerTurnOutcomeTest` (only clean turns reach the hook), `AlertStoreContract.outputMatchRuleRoundTrips` (ITs 28/28, 14 MongoDB + 14 PostgreSQL). Mutation check: 6 of 6 killed — value without review, hook working for agents without rules, wildcard grammar, no truncation, type-mismatched equality, whole property map copied.
- **Manager:** `alert-rules.test.ts` (path grammar, ops per type, problems), `alerts.test.tsx` (builder, inline path error, actions ops, masked URL hint), `schedules-regressions.test.tsx` (alerts-maintenance has no edit, toggle or delete); operator tests for revision 40.
- **Live check** (dev mode on MongoDB 7 in Docker): an agent with `ratingConfig` and conversation review rated over REST (conversation + reply), `/ratings`, `/ratings/summary`, `/ratings/attention-count`, `PATCH /ratings/{id}` (count 2 → 1); a RATING rule with inbox-only delivery produced exactly one event when a 5-star rating was re-rated to 1, and a second low re-rating in the same window counted as `duplicates: 1`; acknowledged. An OUTPUT_MATCH rule on `actions contains handoff_requested` fired once over two turns with `match.value` present (review on). An agent whose LLM points at `127.0.0.1:9` produced one CONVERSATION_ERROR event (`TURN_ERROR`). A webhook rule read back as `https://hooks.example.com/***` everywhere while MongoDB kept the full URL across a PUT that sent the masked value; its delivery stayed PENDING with "signing secret could not be resolved"; `DELETE` and `disable` on `alerts-maintenance` returned 409 `SYSTEM_SCHEDULE`.
- **Full runs:** backend `./mvnw test` 29,924 run, 3 failures, 8 skipped. Two failures (`PostgresIngestionStateStoreTest.recordedDeadlineGoverns`, `PostgresSecretPersistenceTest.timestampsRoundTrip`) are off by exactly one hour because the run fell in the night the clocks went back (Europe, 2026-10-25); both pass with the JVM in UTC (77/77) and neither class is touched here. The third is the known-flaky `DocumentExtractorsTest.honoursItsTimeBudget`. Manager vitest: 622 files, 8,961 tests passed; `npm run lint`, `npm run typecheck` and `npm run i18n:check` (6,333 keys) clean.

---

## ✨ feat(mail): EDDI verifies DKIM and DMARC alignment itself — email sender authentication (2026-10-25)

**Repo:** EDDI (`feat/email-dkim`, on top of `feat/email-hitl-notifications` and `feat/email-mail-core`)

The sender-authentication slice of [the email plan](../planning/email-channel-plan.md) (§8, the DKIM/DMARC part of
§21), built ahead of Phase 2 so the IMAP poller can call it. No channel, transport or poller is part of this change.

### The facade Phase 2 calls

- **`SenderAuthenticator.authenticate(byte[] raw, InboundMail parsed)`** (`engine/mail`, `@ApplicationScoped`) →
  `MailAuthentication`: verdict `ALIGNED_PASS` · `PASS_UNALIGNED` · `FAIL` · `NONE`, the signing domain, a fixed-text
  reason (safe to log), a `temporary` flag (a DNS failure or the lookup budget kept a check from running — retrying
  later may authenticate), and one `DkimResult` per evaluated signature (domain, selector, algorithm, `PASS` · `FAIL` ·
  `TEMPERROR` · `TESTING`, reason). The caller passes the bytes it received and the parser's `InboundMail` of those
  bytes (a size mismatch is refused), then attaches the verdict with `InboundMail.withAuthentication`. Never throws.
- **Only an aligned pass authenticates**, and never when the message has anything but exactly one `From` field with
  one mailbox (counted on the raw bytes and from the parser's `MULTIPLE_FROM`/`MISSING_FROM`) or a duplicated
  singleton header (`DUPLICATE_SINGLETON_HEADER`): an unsigned second `From` or `Subject` added above a validly signed
  one is not hashed, so the signature still verifies, while clients show the forged one.
- `MailAuthentication` gains `reason`, `temporary` and `signatures` (the two-argument constructor stays).
- **Judged as of delivery:** `authenticate(raw, parsed, Instant deliveredAt, fallback)` evaluates `x=` and the
  future-`t=` check at the delivery instant — the transport passes the mailbox server's `INTERNALDATE`, the receiving
  server's own clock, never a sender header — so a short `x=` does not expire while the mail waits for the poller or
  the cost budget. The instant is clamped to `[now − eddi.email.dkim.max-delivery-age, now]` (new property, default
  `7d`): a later value is now, an older one is the cap boundary, so a broken or forged value can neither revive an
  ancient signature nor postdate one. Without it, signatures are judged at the current time, as before.
  `DkimVerifier.verify(raw, budget, evaluatedAt)` takes the instant unclamped.
- **Provider fallback**: `authenticate(raw, parsed, SignedProviderVerdict)`, an interface for a verdict delivered under
  a signature the transport checked (SES over SNS). Consulted only when the local check could not run (no raw bytes,
  or every key lookup failed temporarily); never over a local fail, none or pass; alignment is still EDDI's. No
  provider is implemented.

### DKIM (`DkimVerifier`)

- RFC 6376 on the raw bytes: `simple`/`relaxed` header and body canonicalisation in all four combinations,
  `rsa-sha256` (1024–8192-bit keys, exponent ≤ 64 bits; SPKI or bare PKCS#1 `p=`) and `ed25519-sha256` (RFC 8463,
  the JDK's EdDSA). Refused as `FAIL`: `rsa-sha1` (RFC 8301) and other algorithms, `l=` (body-extension attack), a
  signature not covering `From`, `i=` outside `d=`, expired `x=`, `x=` before `t=`, `t=` in the future (5 min skew),
  revoked (`p=` empty) or missing keys, more than one key record, `k=`/`h=`/`s=` that do not admit the signature,
  `t=s` with `i=` ≠ `d=`, malformed or duplicated tags. A `t=y` key gives `TESTING`, not a pass.
- Bottom-up header-instance selection with over-signing semantics (a name in `h=` more often than present binds the
  absence); the signature being verified is never one of its own `h=` instances. Bare-LF input is converted to CRLF.
- Bounds: 5 signatures evaluated (rest counted), 256 KB header block, 100 `h=` names, 8 KB signature field, one body
  hash per canonicalisation, the body checked before the key is fetched (a tampered mail spends no lookup).

### DMARC alignment (`DmarcAlignment`) and the Public Suffix List

- RFC 7489 §3.1.1: relaxed (same organisational domain) or strict (equal) per the `From` domain's `_dmarc` `adkim=`,
  falling back to the organisational domain's record (§6.6.3); no record = relaxed; several `v=DMARC1` records = no
  DMARC; a temporary lookup failure assumes strict and is flagged. `p=` is not applied. **SPF is not evaluated**
  (EDDI never sees the connecting IP).
- **`PublicSuffixList`**: the upstream file bundled unmodified as `src/main/resources/mail/public_suffix_list.dat`
  (MPL-2.0, new `licenses/MPL-2.0.txt`; commit `8437e5262f13`, 2026-10-22), ICANN and private sections, wildcard and
  exception rules, IDN rules in ASCII. `public_suffix_list.properties` records commit and SHA-256;
  `PublicSuffixListTest` fails when the file is missing, small, or differs from that hash. Refresh with the new
  `scripts/update-public-suffix-list.py`. The `.dat` is `-text` in `.gitattributes` so its hash is stable.

### DNS (`MailDnsResolver`, `DnsjavaTxtSource`)

- **dnsjava 3.6.5** (BSD-3-Clause; runtime dependency slf4j-api only, BOM-managed; CVE-2024-25638 and the KeyTrap
  advisories fixed in 3.6.0 and confined to the DNSSEC validator, which is not used). Its jar registers a
  `java.net.spi.InetAddressResolverProvider` that stays inert unless `org.dnsjava.spi.enable=true` — documented in
  `pom.xml` and `security.md`, with a test that fails if the switch's constant disappears.
- New properties `eddi.email.dns.servers` (empty = system resolvers), `.timeout` (5 s, one retry),
  `.cache-max-entries` (10000), `.cache-max-ttl` (1 h; record TTLs and the SOA minimum for negative answers honoured,
  temporary failures not cached), `.max-lookups-per-message` (10; cache hits free). Records over 4096 characters or
  answers over 10 records are refused; multi-string TXT records are joined. No DNSSEC requirement. The `TxtSource`
  seam keeps every test offline.

### Metrics, docs

- `eddi.email.auth{result}` and `eddi.email.dns.lookups{result,cached}`: two panels in the Full Metrics dashboard's
  Integrations row (k8s copy re-synced), described in [`metrics.md`](metrics.md).
- [`security.md` → Mail Sender Authentication](security.md#mail-sender-authentication--dkim-and-dmarc): the rules,
  what an aligned pass proves and does not (the domain, not the person; forwarded and list mail; no ARC; no SPF), the
  PSL refresh and the dnsjava resolver switch. [`configuration-reference.md` → Email](configuration-reference.md#email)
  lists the six properties. [`compliance-data-flow.md` → Other outbound recipients](compliance-data-flow.md#other-outbound-recipients):
  the DNS resolver learns the signing domains, selectors and `From` domains of received mail.

### Tests

- `DkimVerifierTest` (60): the RFC 8463 Appendix A message and key records stored as fixtures
  (`src/test/resources/mail/dkim/`, byte exact) — both signatures pass, also with LF endings; body, header, second-`From`
  tampering and re-folding. The RFC 6376 §3.4.6 canonicalisation example, signed over the canonical text the RFC
  prints. Self-signed RSA-2048 and Ed25519 messages (`DkimTestSigner`, a regex-based signer written independently of
  the verifier) in all canonicalisations, with folded headers, trailing whitespace, 8-bit text, empty and header-only
  bodies; every refusal above; DNS outcomes; bounds.
- `SenderAuthenticatorTest` (31): verdicts, DMARC through the facade (subdomain relaxed/strict, org-domain fallback,
  `d=co.uk`, DMARC tempfail), header injection (second `From`, two mailboxes, no `From`, second `Subject`, mismatched
  bytes, and each of the two From guards on its own against a parser that disagrees), judging as of delivery
  (expired since delivery passes; a delivery instant older than the cap or in the future is clamped), provider fallback, 300 random inputs, and a hostile message (5000 header lines, 50 signatures × 100 `h=`
  names, 10 MB body) within a 5 s budget.
- `DmarcAlignmentTest` (9), `PublicSuffixListTest` (24), `MailDnsResolverTest` (13; three run dnsjava against a
  loopback UDP server built in the test).
- Every verdict path was mutation-checked: the check reverted, a test failed, the check restored.

### For Phase 2

- Call `authenticate` with the transport's bytes right after parsing; keep `temporary` in mind (leave the message
  for the next poll rather than treating a DNS outage as an unauthenticated sender).
- Pass the mailbox server's `INTERNALDATE` as `deliveredAt`; without it, `x=` is judged at processing time.
- `eddi.email.auth` carries only `result`. Prometheus requires one tag set per meter name, so per-integration tags
  mean moving the counter to the channel layer rather than adding tags here.
- No real-world signed mail (Gmail, Outlook.com, Exchange Online, a list) is in the corpus yet (§21); add it with
  its DNS records when a test mailbox is available.

---

## fix(manager): https-only webhook check, bulk-acknowledge mock, operator revision 41 (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the alerts backend review fixes (PR #1138)

### What changed and why

- **Rule editor:** `ruleProblems` ([`alert-rules.ts`](../ui/manager/src/lib/alert-rules.ts)) accepts only an `https://` webhook URL or a `${vault:…}` reference, as the server now requires; the masked `https://host/***` of a stored URL still passes.
- **MSW:** the bulk-acknowledge handler answers 400 `INVALID_PARAMETER` to an empty `ids` list or an empty entry, as the server does; the Manager sends `{}` when it acknowledges by filter and a non-empty list otherwise, so its calls are unchanged.
- **Platform Operator:** the prompt says webhooks must be https and corrects "no conversation text" — a rating comment, or an `OUTPUT_MATCH` value with `includeValue` and conversation review on, can appear, each at most 280 characters. Revision 40 → 41.

### Review follow-ups

- `AlertDeliveryService` derives the send claim lease as the longer of 30 s and three times `eddi.alerts.webhook-timeout`, and `AlertSettings` keeps that timeout between 1 s and 60 s, so a long timeout can no longer make every send skip.
- Manager: test-result rows in the rule editor have unique keys; removing a chat rating clears its reasons and comment; the chat hook reads a conversation's own rating controls even when the current profile is null; the feedback list steps back to the last existing page when triage empties the current one.

### Tests

`npm run typecheck`, eslint on the changed files and `npm run i18n:check` pass; the alert and operator suites (19 files, 530 tests) pass.

---

## fix(manager): second round of alerts review findings — throttle bounds, bulk-acknowledge body, inbox paging (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139)

### What changed and why

- **Throttle checked before saving:** `throttleProblems` in [`alert-rules.ts`](../ui/manager/src/lib/alert-rules.ts) uses `AlertRuleValidator`'s bounds exactly (whole numbers, 1–1440 minutes, 1–1000 per window); `ruleProblems` reports `throttle`, which disables Save and Send test, and the editor marks the field with an inline error (`alerts.throttleRange`, all 11 locales).
- **Bulk acknowledge body:** `acknowledgeAlertEvents` sends `{}` only for `null`/`undefined` and `{ ids }` otherwise, so an empty list reaches the server, which refuses it, instead of being widened to everything matching. The page's only "all matching" caller passes `null`.
- **Inbox:** keyed on the agent, so changing it starts from page 1 with nothing selected; the page moves back to the last one that exists when acknowledging empties the one it is on (instead of "No open alerts"); the selection clears when the source or the acknowledged filter changes.
- **Schedules:** a dead-lettered server-owned schedule (`alerts-maintenance`) gets no Retry on its row and no Retry/Dismiss in Failed fires — the server answers 409 `SYSTEM_SCHEDULE` to both.
- **Output-match builder:** switching the path source to `actions` turns the value into text (contains compares strings) and keeps `exists` without one.
- **Full-stack e2e spec:** each resource is recorded as soon as it exists, and cleanup removes every recorded one even when setup failed midway.

### Tests

New: `output-match-builder.test.tsx` (5), `lib/api/__tests__/alerts.test.ts` (3), throttle cases in `alert-rules.test.ts`, four page tests in `alerts.test.tsx` (throttle error, page clamp, selection reset on filters, fresh inbox on agent change) and the server-owned case in `schedules-regressions.test.tsx`. The alerts, schedules, operator, mocks and i18n suites: 34 files, 770 tests. Mutation check: 10 of 10 mutants killed (throttle not reported, window bound off by one, fractions accepted, empty ids widened, no page clamp, selection kept on either filter, Inbox not keyed on the agent, row Retry and Failed-fires actions for a server-owned schedule). The Playwright spec is typechecked and linted, not run.

---

## fix(manager): carry each webhook target's targetId through the rule editor (2026-10-25)

**Repo:** EDDI (`feat/alerts-manager`, PR #1139) — the Manager side of the backend's `targetId` restore (PR #1138)

### What changed and why

- **Types:** `DeliveryTarget.targetId` in [`lib/api/alerts.ts`](../ui/manager/src/lib/api/alerts.ts), documented as the server's id to send back unchanged.
- **Editor** ([`alert-rule-editor.tsx`](../ui/manager/src/components/alerts/alert-rule-editor.tsx)): targets are edited, removed and — new — moved up or down as whole objects, so each keeps its own `targetId`; rows are keyed by it. A new target has none. Save and Send test send the ids as loaded. Two new strings (`alerts.moveTargetUp`, `alerts.moveTargetDown`) in all 11 locales.
- **Validation:** `ruleProblems` accepts a masked URL only together with a `targetId`, so a masked URL is never sent without one.
- **MSW** ([`alert-handlers.ts`](../ui/manager/src/test/mocks/alert-handlers.ts)) now mirrors the server: it stores full URLs and answers reads masked, assigns ids on create (replacing client ones), restores a masked or empty URL on update and test send only from the stored target with the same `targetId`, and refuses one without a known id with 400 `INVALID_RULE`.
- **Platform Operator:** the masked-URL paragraph explains `targetId` and the `INVALID_RULE` refusal. Revision 41 → 42; it ships as **43**, because merging main (where the ratings work had moved the operator to 39) needed a revision above both.

### Tests

New page tests (`alerts.test.tsx`): removing one of two masked same-host webhooks sends the other with its own id, reordering moves ids with their targets, editing a masked target's secret keeps its id, a new target goes without one, a test send of a saved rule sends the ids, and saving the stored webhook rule round-trips through the mock. `alert-handlers.test.ts` (4) pins the mock's contract; `alert-rules.test.ts` covers the masked-URL check. `npm run typecheck`, the full `npm run lint` and `npm run i18n:check` (6,336 keys) pass; the alerts page, alert components, `src/lib` (operator included), `src/test/mocks` (OpenAPI contract included), schedules and i18n suites: 143 files, 2,518 tests. Mutation check: 5 of 5 killed (edit drops the id, removal re-pairs ids by position, reorder leaves ids behind, masked URL accepted without an id, mock restoring without matching the id).

---

## fix(alerts): review findings on the alerts backend — https-only webhooks, GDPR export, digest healing, bulk acknowledge (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138; Manager follow-ups on `feat/alerts-manager`, PR #1139)

### What changed and why

- **Webhook URLs must be https.** `AlertRuleValidator` refuses a plain `http` URL at save time; `AlertSenders` checks again at delivery, which is the only place a `${vault:…}` URL is known — one that resolves to `http` (or to no host) is not delivered to. STRICT SSRF and no redirects are unchanged. Docs: [Alerts → Delivery](alerts.md#delivery).
- **GDPR export** (`AlertGdprParticipant.export` returned `null`): it now lists the rules the person created or last edited (id, name, created/updated time, `author` and/or `lastEditor`) and the events they acknowledged (id, rule name, source, agent, fired and acknowledged time), at most 10,000 of each, and no other person's identity or event content. New store reads `IAlertRuleStore.findByAuthor` and `IAlertEventStore.findAcknowledgedBy`, implemented for MongoDB and PostgreSQL and covered by `AlertStoreContract`. Docs: [Alerts → Privacy](alerts.md#privacy), [GDPR Compliance](gdpr-compliance.md).
- **A digest could stay stuck.** When the digest's insert hit its dedupe key — another runner sent it, or this one crashed between the insert and marking the held events — the held events stayed `DIGEST_PENDING` and every pass retried a digest that never inserted. `AlertMaintenanceService.sendDigest` now marks them on a dedupe hit too, still without delivering.
- **`actions exists` fired on every clean turn**: an empty action list is now absent (`OutputMatcher.resolve`); `contains` is unchanged.
- **A cache miss copied the turn even when nothing would run it**: when submitting the output-needs loader fails (alerts off, queue full), `OutputMatchHook` returns before building a snapshot.
- **Bulk acknowledge:** a present `ids` list is the selector. An empty list or an empty entry is a 400 `INVALID_PARAMETER` instead of falling through to "everything matching the filter"; the filter applies only when `ids` is left out. The MCP tool acknowledges one event by id and the Manager sends `{}` when acknowledging by filter, so both stay valid.
- **Wording:** `OUTPUT_MATCH` added to the `source` tag of `eddi_alerts_fired_total` ([Metrics](metrics.md#alert-metrics)), to `AlertEventQueryParams.source` and to `list_alert_events`' description and `source` argument. "No conversation text" is corrected wherever it was repeated (REST Javadoc, MCP description, [Alerts](alerts.md), [MCP Server](mcp-server.md), the `eddi-admin` skill): a rating comment and, only with `includeValue` and conversation review on, an `OUTPUT_MATCH` value appear, each cut to 280 characters.

### Design decisions

- **On a digest dedupe hit every held event is marked**, including any held after the existing digest was written. They stay in the inbox but are not listed in a digest message. The alternative, reading the existing digest to mark only what it covered, needs the event ids on the digest, which it does not store.
- The export re-checks each row against the user id, so a store returning a wider set cannot leak another person's rule or acknowledgement into it.

### Tests

448 unit tests across the alert, output-match, REST, MCP, step-runner and GDPR classes pass, among them the new `AlertGdprParticipantTest`, `RestAlertEventsTest.bulkEmptyIdsRefused`, `AlertMaintenanceServiceTest.digestCrashBetweenInsertAndMark`, `OutputMatchHookTest.cacheMissLoaderRefused`, `OutputMatcherTest.emptyActionsAreAbsent` and `AlertSendersTest.httpsOnly`. Store ITs: 32/32 (16 MongoDB + 16 PostgreSQL, with the two new contract cases). The doc and style guards pass. Mutation check: 9 of 9 mutants killed — http accepted at save, an http vault URL delivered to, an empty export, another person's acknowledgement exported, the lastEditor role lost, a dedupe hit leaving events pending, a refused loader submit still snapshotting, an empty ids list falling back to the filter, a blank id entry accepted.

---

## fix(alerts): erasable output values, queued rating alerts, exact digest recovery, targetId-based URL restore (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138) — the second round of review findings

### What changed and why

- **OUTPUT_MATCH values can be erased.** An event that stores a matched value (only with `includeValue` and conversation review on) also records the conversation owner in an internal field, `AlertEvent.subjectUserId`. It is `@JsonIgnore`d, written by both stores and never read back, so no REST row, MCP answer, operator answer, Slack message or webhook payload can carry it (asserted on the serialized row and on both payloads). Events without a stored value never get it. `AlertGdprParticipant.erase` blanks the value and the field (`IAlertEventStore.redactOutputValues`, partial index on both stores); the owner's export lists those values (value, conversation, time) under `outputMatchValues` — their own conversation content, as the ratings export lists their ratings.
- **A rating alert queued across an erasure.** `AlertEngine.evaluateRating` re-reads the rating first and skips it when it is gone or its `updatedAt` changed (the newer save has its own alert). After the event is written it re-reads again; if the rating was erased in between, the event's excerpt is blanked (`redactRatingComment`) before anything is delivered. No per-user lock.
- **Digest recovery marks exactly what the digest covers.** The digest records the held event ids it covers (`digestEventIds`, internal, at most the 500-event batch); on a dedupe hit the maintenance pass reads that digest (`findByDedupeKey`) and marks only those ids, so events held later stay `DIGEST_PENDING` for the next digest.
- **Webhook URLs are restored by `targetId`, never by position or host.** Every delivery target gets a server-assigned `targetId`, kept across updates and returned by every read. An update or test send that sends a masked or empty URL keeps the stored URL of the target with the same `targetId` only; a masked or empty URL without a known id is a 400 `INVALID_RULE`; a new target must bring a real URL; ids the stored rule does not have (and all ids on a create) are replaced. Removing or reordering two masked targets on one host no longer hands one target the other's URL.
- [Alerts → Privacy](alerts.md#privacy) now says what is stored: the acknowledger's id (shown in the inbox, never sent out) and the internal subject id of a stored output value, both until erasure or retention.

### Design decisions

- **Rules saved before ids existed need no migration.** A target without an id gets `t` + its existing hashed `key()` on every read — the same id each time — so the first update of such a rule matches the ids the client read, and that save stores them. Both stores already persist targets as JSON, so `targetId` is stored without a schema change.
- **The subject id is write-only.** Erasure and the export query by it in the store; no read maps it back into an event, which is a stronger guarantee than hiding it at each serializer.
- **A digest written before covered ids existed** would match nothing on a dedupe hit. Alerts have not shipped, so no such digest exists outside development databases.

### Tests

477 unit tests across the alert, output-match, REST, MCP, step-runner, GDPR and rating classes pass, including the new `AlertPrivacyRaceTest` (both race orderings, the subject recorded only with a value, never serialized or sent) and the `targetId` cases in `RestAlertRulesTest` (remove one of two same-host targets, reorder, new target, unknown id refused, client ids replaced, legacy rule). Store ITs: alert stores 20/20 on MongoDB and on PostgreSQL (new contract cases: erasable subject, covered ids and `findByDedupeKey`, one-event excerpt redaction, persisted `targetId`), rating stores 15/15 on each. Mutation check: 9 of 9 for the erasure, race and digest changes, 5 of 5 for the `targetId` match.

---

## fix(alerts): erasure fence for queued alert work, deliveries send the stored event (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138) — two more review findings

### What changed and why

- **A queued OUTPUT_MATCH value could land after an erasure.** [`AlertErasureFence`](../src/main/java/ai/labs/eddi/engine/alerts/AlertErasureFence.java) is a `UserErasureParticipant`: the erasure's step 0 (`stopInFlightWork`, and `stopInFlightWorkByHash` on every other node through the existing cluster `gdpr-stop` broadcast, as `ConversationService` and `AuditLedgerService` receive it) records the time the erasure started, per user, keyed by the user id's hash. `TurnSnapshot` now carries its capture time. `AlertEngine.evaluateOutput` stores no value (and no subject) when the snapshot was captured before a live fence — the alert still fires — and after the insert re-checks the fence and blanks value and subject (`redactOutputValues`) before delivery when the fence went up meanwhile. Queued rating alerts use the same fence on top of the re-read: a fenced rating is not evaluated, and an excerpt written while the fence went up is blanked. Fences last 15 minutes (`AlertErasureFence.FENCE_TTL`).
- **A hand-made schedule could pass for `alerts-maintenance`.** The schedule API refused only bodies carrying the alert marker, so a plain schedule named `alerts-maintenance` could be created, and `AlertMaintenanceSchedule.ensure` — which looked the schedule up by name — then took it for the installed one, leaving retries, digests and retention unprocessed. The API now also refuses the reserved name (trimmed, case-insensitive) on create and on a rename (`AlertSchedules.isReservedName`), and `ensure` counts only a row carrying the maintenance marker; a same-named row without it (possible on a deployment from before the name was reserved) triggers a bounded scan for the marked row, and the real schedule is created when none exists. `AlertMaintenanceScheduleTest` and a new `RestScheduleStoreExpandedTest` case cover it; the docs now say a rating alert captured before an erasure is dropped altogether, while an `OUTPUT_MATCH` alert fires without its value.
- **Delivery could send a copy older than the store.** `AlertDeliveryService.deliver` skips an event that no longer exists and reads the event again right before each send, sending the stored (possibly redacted) version — first delivery and every retry, on any node. No cross-node lock: the remaining window is between that read and the send, and a send already on its way cannot be recalled (said in the code and in [Alerts → Privacy](alerts.md#privacy)).

### Design decisions

- **A dedicated fence bean**, not the dispatcher or the engine: it has one job, both the engine and the cluster handler reach it through CDI, and it keeps the engine free of erasure bookkeeping.
- **The fence only holds back content captured before the erasure started**, so expiry is a matter of memory, not correctness: a later conversation of a reused id is never blocked, and 15 minutes is far longer than the alert queue takes to drain.

### Tests

486 unit tests across the alert, output-match, REST, MCP, step-runner, GDPR, rating and cluster-handler classes pass, including the fence cases in `AlertPrivacyRaceTest` (captured before the fence stores no value, fence up between check and insert blanks before delivery, captured after keeps the value, expiry, set by hash, fenced rating not evaluated) and `AlertDeliveryServiceTest` (first delivery and a retry send the redacted stored version, a deleted event is not sent). Mutation check: 7 of 7 killed.

---

## 🐛 fix(email): HITL email notification review findings (2026-10-25)

**Repo:** EDDI (`feat/email-hitl-notifications`)

Eight review findings on the HITL email notifications ([`docs/hitl.md`](hitl.md), section "Email notifications").

### What changed and why

- **GDPR erasure keeps the user directory entry when an earlier participant failed**
  (`GdprComplianceService`). The email ledger finds a person's rows by the address in the user directory, which is
  why the directory participant runs `LAST`. The loop did not link the two: with the ledger step failing, the
  directory entry was still erased, so the re-run the result asks for could no longer find those rows and they
  stayed until ledger retention. A `LAST` participant is now skipped, and reported as failed, when an earlier
  participant failed (the same reasoning as step 4c, which keeps the snapshots for the retry).
- **A pause on the conversation-start turn is announced only after the descriptor is written**
  (`ConversationService.startConversation`). A failed descriptor write discards the conversation again, and the
  asynchronous notifier could already have mailed about it. The say and resume sites already fire after the
  conversation is final.
- **`eddi.mail.smtp.connection` must match the connection-name grammar** (`SystemMailer.parse`). A name no stored
  connection can carry (`"my mail"`) used to leave the mailer reported as enabled with every send failing; it now
  disables the mailer at startup with the reason.
- **The email ledger caps `providerId` (255) and `lastError` (512) on every write, in both stores**
  (`IEmailMessageLedger.cap`, Mongo and PostgreSQL). Those are the PostgreSQL column widths; an uncapped render
  error made PostgreSQL refuse the update, which left the row claimed until its lease ran out and retried it after
  every lease. The cap never splits a surrogate pair.
- **A timeout past the end of the time-line no longer aborts the notification** (`HitlNotificationContent`). A
  positive duration such as `P1000000000000D` passes config validation but overflows `Instant`; the deadline line
  now names the duration instead of a date. `HitlEmailNotifier.attempt` also treats any runtime failure while
  building the mail as a terminal `FAILED` row, instead of letting it escape and leave the row `PENDING`.
- **Manager: Save is refused while the recipients box is invalid** (`HitlConfigSection` reports it through
  `onValidityChange`, `agent-detail.tsx` refuses every save path, as it does for the spend limit). An invalid list
  never enters the draft, so an earlier valid edit was saved while the field showed something else. The recipients
  label is now associated with its textarea, and the hint no longer says the mail carries no conversation content:
  a tool-call pause lists the pending tools with redacted, shortened arguments (all 11 locales, plus the new
  `agentDetail.hitlNotifyFixBeforeSave` message).

The operator system prompt already described the content correctly (it names the redacted tool arguments), so it is
unchanged.

---

## feat(alerts): alert rules framework — ratings and failed turns to inbox, Slack and signed webhooks (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 5a of [`planning/conversation-rating-plan.md`](../planning/conversation-rating-plan.md) (§0.2, §11)

### What changed and why

People running an agent can now be told when something needs them: a low rating or a comment, or a turn that failed, was interrupted, ran past its deadline or was stopped by a cost limit. One mechanism with sources, evaluated outside the pipeline — see [Alerts](alerts.md), whose top section is the design note.

- **Model and stores** — `AlertRule` and `AlertEvent` ([`engine/alerts`](../src/main/java/ai/labs/eddi/engine/alerts/)), MongoDB (`alertrules`, `alertevents`, `alertwindows` with a TTL index) and PostgreSQL (`alert_rules`, `alert_events`, `alert_windows`) twins selected in `DataStoreProducers`. `OUTPUT_MATCH` is not accepted yet; its fields slot into `AlertCondition` and a constant into `AlertSource`.
- **REST** — `/alerts/rules` (CRUD + `POST /alerts/rules/test`) and `/alerts/events` (list, unacknowledged count, read, acknowledge one / bulk, retry), save-time validation (`AlertRuleValidator`, every problem named), coded errors (`AlertErrors`).
- **Hooks** — ratings: a CDI event after the rating is stored (`ConversationRatingSaved`). Turns: `ConversationStepRunner.waitForExecutionFinishOrTimeout`, the one method the say path and the HITL resume path both wait in, now classifies the outcome from in-memory data (ERROR, cancelled / EXECUTION_INTERRUPTED, watchdog timeout, an `llm:spend_refused:*` record) and fires `TurnOutcome` only for a bad one. `AlertDispatcher` observes both and only enqueues onto one worker with a bounded queue; a full queue drops with `eddi.alerts.dropped` and a rate-limited WARN.
- **Cluster-safe spam control** — the dedupe is a unique index over `ruleId|agentId|conversationId|windowStart`; the per-rule cap an atomic window counter (`$inc` upsert / `INSERT … ON CONFLICT DO UPDATE RETURNING`); delivery updates and claims are conditional on a version (Postgres claims with `FOR UPDATE SKIP LOCKED`); digests mark only events still `DIGEST_PENDING` and carry a dedupe key of their own.
- **Delivery** — Slack through the channel integration that serves the channel (`ChannelTargetRouter` bot token + `SlackWebApiClient`), mrkdwn-escaped; webhooks through `OutboundHttp` (STRICT SSRF, no redirects, timeout, 64 KB body cap, 16 KB answer read) signed `X-Eddi-Signature: t=…,v1=…` with `HmacSignatures` — the scheme EDDI's own inbound webhook channel verifies; the signing secret must be a `${vault:…}` reference and an unresolved one stops the delivery. The inbox always.
- **Retries, digests, retention** — one `alerts-maintenance` system schedule (HEARTBEAT, 60 s) routed by a metadata fast-path in `ScheduleFireExecutor` to `AlertMaintenanceService`, so it runs under the poller's cluster-wide claim and lease. Backoff 30 s × 4ⁿ⁻¹, 5 attempts. An idle pass writes no fire log. User-created schedules claiming `alertType` are refused.
- **Privacy** — no user id and no conversation text anywhere an alert goes; the payload is built field by field (`AlertPayloads`); the rating comment is cut to 280 characters, and GDPR erasure blanks it (`ConversationRatingGdprParticipant` redacts before deleting the ratings) and forgets acknowledgers and rule authors (`AlertGdprParticipant`).
- **Manager** — Alerts page ([`pages/alerts.tsx`](../ui/manager/src/pages/alerts.tsx), `/manage/alerts`, admin + editor): inbox with source / acknowledged filters, per-target delivery status, Retry, acknowledge one / selected / everything here; rules list and editor (source picker, scope, condition, Slack and webhook targets, "Send test alert", throttle and digest, delete with confirmation). Sidebar badge of unacknowledged alerts, administrators only, polled every 30 s. 99 alert keys plus `nav.alerts` in all 11 locales; MSW handlers ([`alert-handlers.ts`](../ui/manager/src/test/mocks/alert-handlers.ts)); `openapi-operations.json` regenerated.
- **MCP** — `list_alert_events`, `acknowledge_alert_event` (`McpAlertTools`, AUTHOR tier, in-process bean); `McpRatingTools`' coded-error mapping moved to `McpToolUtils.codedFailure` and shared. 95 tools.
- **Operator** — reads `GET /alerts/events`, `/alerts/events/unacknowledged-count`, `/alerts/rules`, `/alerts/rules/{ruleId}`; prompt section on rules, states, retries and scoping; revision 37 → 38.
- **Docs** — [Alerts](alerts.md) (new, in `SUMMARY.md`), [Metrics](metrics.md#alert-metrics), a Grafana alert-rule example in the [Monitoring Guide](monitoring/monitoring-guide.md#alerting-on-alert-delivery), an Alerts row in the full metrics dashboard (synced to `k8s/overlays/monitoring`), the `eddi.alerts.*` properties in the [Configuration Reference](configuration-reference.md#alerts), [MCP Server](mcp-server.md#alert-tools-2), the `eddi-admin` skill.

### Design decisions

- **The turn hook sits in the wait, not at error sites.** `waitForExecutionFinishOrTimeout` is where ERROR, interruption and the watchdog's deadline converge for both turn paths, and `BaseRuntime` runs the turn's callbacks before `Future.get` returns. A mid-loop cost-ceiling stop is not visible there (the loop ends with an answer); the next turn's refusal is.
- **One maintenance schedule, not one per event or rule.** Fewer rows in the schedule list, one claim per minute; every step it takes is a conditional write, so a duplicated schedule (two nodes creating it at once) is harmless.
- **Editors: one agent, Slack and inbox only.** A rule over several agents and a webhook target (arbitrary URL, signed with a vault secret) are an administrator's. An editor sees a webhook by host only. Access is re-checked against the stored rule on update and delete.
- **Fixed, epoch-aligned windows** for dedupe and cap — exact across replicas through unique keys and atomic counters, at the price of a boundary where two windows meet.
- **The rule author, the acknowledger and the reviewer come from the caller**, never the body; MCP and the operator use the same in-process resources.
- **Operator: reads only.** Acknowledging is a person's statement; retry and test send real messages; rule writes decide where alert content goes. A rule read shows a webhook URL to the operator (it runs as the admin); the prompt says not to repeat it.

### Tests

Backend unit: 129 in the alert, step-runner, GDPR and MCP classes run together, plus `ScheduleFireExecutorTest` and `RestScheduleStoreExpandedTest` (648 with the related service suites); "never blocks" is proven twice — `AlertDispatcherTest` (10,000 hand-offs to a stuck worker in under a second) and `ConversationStepRunnerTurnOutcomeTest` (2,000 failing turns through the real hook). ITs: `MongoAlertStoresIT` and `PostgresAlertStoresIT` share `AlertStoreContract` (13 each, incl. a 40-thread window-counter race, the unique dedupe, version-conditional updates and single claims). Mutation check: 22 of 22 mutants killed by a test failure — every editor scoping guard (agentId required, single-agent scope, no webhooks, the EDIT check, agent-less events, webhook redaction, the stored-rule re-check on update and delete, editor rule listing, per-id bulk acknowledgement, single acknowledgement), the author from the caller, the privacy guards (comment truncation, no comment in the summary, Slack escaping, GDPR redaction), the hook never throwing into the turn, deadline classification, a full queue never throwing, and the webhook's strict SSRF, no redirects and unresolved-secret refusal. Manager: 18 new tests (page, editor, badge, `ruleProblems`). Full backend `./mvnw test`: 29,895 run; the two coverage guards it caught (configuration reference, dashboard) are fixed, the third failure is the known-flaky `DocumentExtractorsTest.honoursItsTimeBudget`.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
| 2026-10-24 | Self-continuation is delivered as a one-shot schedule on the cluster-aware schedule machinery, not through the conversation coordinator's queue | E8 needs a continuation that survives a node crash, runs once under the conversation lease, and lands in dead letters when lost | Coordinator queue (in memory, lost on a crash); a custom scheduler (AGENTS.md forbids it) |
| 2026-10-24 | `maxContinuations` counts per conversation (steps carrying the engine-written `continuation:number`), not per chain | A counter the author or a property setter can reset is no bound; a job that needs more starts a new conversation | A conversation property as counter (writable by config); per-chain counts reset by user input |
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
| 2026-10-24 | Hidden-content pass in two modes: conservative for pages (comments, 1px, transparent, explicitly same-coloured text), strict for mail | Pages hide accordions, tabs and "read more" content with display:none/hidden/opacity until script reveals it — content RAG wants; mail runs no script, so anything hidden there is only for a machine | One strict rule set for every caller (dropped FAQ answers and tab panels from crawled pages); opt-in only for mail (leaves RAG without the white-on-white and zero-size defences) |
| 2026-10-24 | Style-block hiding rules over-approximate instead of being capped | A rule cap is a bypass: a display:none rule placed after the cap would leave the hidden text in | Truncating the style text or the rule count; exact selector matching per rule (one document search per rule, quadratic) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
| 2026-10-24 | Single-agent revise is `hitlConfig.onRejection: REVISE`, RULE pauses only, the frozen request kept as step data | Plan decision 9.5; a rejected tool call already reaches the model with the note | A new verdict value on the approval API; rebuilding the request from memory at resume (loses retrieved context and tool results) |
| 2026-10-24 | `maxReworks` runs the rework inside the VERIFY phase | The phase list stays linear and resume bookmarks untouched | Jumping the phase loop back to EXECUTE |
| 2026-10-24 | A guardrail-refused message is withheld from every later model-facing history by a step flag written at block time (`llm:input_withheld:<taskId>`), not by re-screening | The classifier does not re-screen history, so a message it blocked reached the model on the next turn | Making the classifier screen history (cost multiplies by the window); storing the redacted text in memory (size, and a classifier redaction is the block message anyway) |
| 2026-10-24 | `postResponse` runs after an input block, like every other fallback | An input-blocked `convertToObject` + `addToOutput: false` task answered nothing | Correcting the docs instead — that left the silent empty answer |
| 2026-10-24 | Webhook dedup refuses (503) per integration (own budget, fair share from half capacity, hard stop at 90%) rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
| 2026-10-15 | LLM input/output guardrails use an EDDI-native `ContentGuardrail` SPI, not `dev.langchain4j.guardrail` | WS4/R0 implementation against langchain4j 1.22.0 | lc4j `InputGuardrail`/`OutputGuardrail` + `EddiChatExecutor` (mandatory executor = unbudgeted model-call path; sealed results; mandatory builder fields) |
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in, `eddi.chat.url-token.enabled`, default off; served to the widget as a script global before the bundle | Security review M6: a token in the URL is login CSRF and is logged by the first request | Keeping it on and documenting the risk; a fetched JSON config, which would race the `?token=` read |
| 2026-10-15 | All three CSP filters use negative Vert.x orders so they run before the `/q` sub-router mount | Security review L11: at order 20 the Swagger filter never ran, and Quarkus passes the order to Vert.x unchanged, ascending | Moving Swagger UI off the sub-router, or a Java `Filters` observer — both heavier than three numbers |
| 2026-10-15 | `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false`; the advertised MCP resource scheme follows the request | Security review L3: a plain-http instance with auth on advertised an https identifier nothing served | Keeping `true` (wrong on every plain-http instance); deriving it from `quarkus.http.proxy.*` (no config signal separates plain http from a TLS proxy without forwarded headers) |
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
| 2026-10-15 | Tool cost ceilings enforced by default (`eddi.tools.budget.enforce-by-default=true`); `enforceBudget: false` is the named opt-out | Security review M8: every stored ceiling was advisory because the opt-in key was undeclared, and the $0.00 built-in pricing that justified the opt-in was fixed releases ago | Keep the opt-in and only document it |
| 2026-10-15 | Remote MCP/A2A tool parameter schemas are governed (text redaction, per-tool and per-server byte caps; descriptions stripped before a tool is dropped) | Security review M7: schemas are prompt content authored by the remote party, and a partial schema would make the model call the tool wrongly | Govern descriptions only; truncate the serialised schema mid-document |
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
| 2026-10-13 | Model-version rules live in a data catalog consulted when a model is built, rejected parameters are dropped with a WARN (switchable) | Exposing 30 more parameters per provider without knowing which model accepts them would make agents easier to break; dropping a certain-4xx parameter keeps the agent answering | Fail the build of the model; only document the rules |
| 2026-10-13 | No catalog rule without an official source; unknown models fail open | A wrong rule silently removes a working setting, a missing rule only costs a visible vendor error | Infer rules from model-name prefixes |
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
| 2026-10-07 | Operations whose required input the generator cannot send are skipped and reported, not generated half-working | An approved `updateResourceUri` call could only ever fail, and the model could not say why | Generating them and warning (the tool still exists for the model to call); failing the whole setup (one unusable endpoint would block every other tool) |
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
| 2026-10-13 | The Kubernetes copy of the Full Metrics Reference ships as two linked dashboards | Keeps plain client-side `kubectl apply -k` working; the source dashboard is unchanged | Requiring `kubectl apply --server-side` (changes every operator's apply command); raising the test's budget (removes the headroom that caught this) |
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together. Superseded on 2026-10-12: the post-release PR moved the chart to 6.5.0, so the examples were bumped with it when main was merged | Writing 6.5.0 now (fails the build until the chart moves) |
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
| 2026-10-02 | HITL timeouts whose decision cannot be applied yet are re-armed at a fixed interval and never dead-lettered | A COMPLETED-and-disabled one-shot silently turned a finite policy into wait-forever | Retrying on the normal backoff ladder (dead-letters after ~21 min) |
| 2026-10-02 | Schedule fires re-check USE against a creator access snapshot (teams + admin flag) taken at creation | principalMayUse(id, principal) refuses team shares; server has no team membership without a token | Re-checking the bare principal; skipping the check |
| 2026-10-02 | Scheduler lease is raised at startup to outlast fire-timeout + grace instead of failing startup | A misconfigured scheduler should not take the platform down | Failing startup; heartbeat-renewed leases (more store surface) |
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
| 2026-09-29 | Merge with main: a captured conversation property is kept at turn end; a secret input shorter than 4 characters is removed as a whole token, in values only | The wizard pattern hands a captured key to a later turn; a short PIN echoed into a reply must not persist, but digits inside other numbers and field names must | Main's property scrub; no search below 4 characters; renaming map keys in token mode |
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
| 2026-09-29 | PR #831 keeps #859's `ClientContextGuard` and #860's converse ownership check, drops its own `ReservedContextKeys` and started-conversation set, and applies the delegation USE check to continuations | Two implementations of one boundary drift apart; the USE check on continuations closes the one case the started set covered that ownership does not | Keeping the started set alongside the ownership check (two continuation rules for one tool); stripping in `ConversationService` as well (a second boundary for the same keys) |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
| 2026-09-29 | KEK rotation keeps main's rollback and adds re-runnability only for what a rollback cannot undo | Both branches fixed "a half-way rotation strands DEKs"; one mechanism, main's, with the pending salt and either-KEK verify for the unrecoverable cases | Forward-only re-run (this branch's original); rollback without the pending salt (loses DEKs when the salt write fails) |
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
| 2026-09-27 | A client-flagged secret input is searched for from 4 characters, not 8 and not 1 | A PIN or short password is what a password field carries; below 4 the replace-everywhere search shreds the turn's reply and map keys for a guessable value. Same floor in the turn-end scrub, read-time masking and audit buffer | The 8-character context-value floor; every nonempty form |
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | A client-flagged secret input is scrubbed when its turn ends, and the turn output's `input` is the masked display copy on the wire | The parser overwrote the placeholder with the normalized plaintext, so exposing `input` alone would have leaked a copy | Exposing `input` without the engine fix; scrubbing properties the designer captured the input into (breaks the wizard pattern) |
| 2026-09-26 | Older stored secret turns are masked on read, not migrated | Every secret turn carries `context.secretInput`; masking on read closes the exposure for history at no migration cost | A data migration; leaving history exposed |
| 2026-09-26 | Chat UI math is `$$…$$` only | Single-dollar math turned dollar amounts into formulas | remark-math defaults |
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
| 2026-09-26 | `RestAgentStore.updateAgent` keeps the stored `dynamicOrigin`; duplicate and new-agent ZIP import drop it; create still accepts it | The marker authorizes permanent deletion, so only the engine may write it; setup writes it through the loopback REST create | Stripping on create (breaks `AgentSetupService`); a separate engine-only store field (larger change, same outcome) |
| 2026-09-26 | Reserved context keys are read with exact-key lookups (`getData`, new `getExactDataPerStep`) **and** `ClientContextGuard` also drops keys that start with a reserved name | `getLatestData`/`getAllLatestData` match by prefix, so `groupIdSuffix` and friends passed the exact-name guard and were read as the reserved key (PR #831, CWE-863) | Only the exact lookups (the next prefix read reopens it); only the prefix drop (leaves every reader relying on a distant boundary); changing `getLatestData` to exact matching (many callers rely on the prefix, e.g. `httpCalls:*`, `output:*`) |
| 2026-09-26 | `teardown_agent` and end-of-discussion cleanup require a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
| 2026-09-21 | Walk `docs/` rather than list it in `ComposeStackTest`, and assert the sweep's shape | A guard that reads only the top level would have failed over a nested page that *does* document a stack; a false failure is how a guard gets deleted rather than fixed | Listing the top level only (the reviewed version), naming the nested directories explicitly (a third place to forget when one is added) |
| 2026-09-29 | Each Slack approval card carries a random card id in its buttons, recorded with the card; a click must present the id recorded for the current pause | Subject + pause matching let an old card of the same subject approve a newer pause | Storing the posted message `ts` on the record after `chat.postMessage` (a post-then-update window, and a failed update leaves a live card unusable) |
| 2026-09-28 | The Slack workspace comes only from a declared `platformConfig.teamId`, for owned channels too; without one the identity is team-less | A signing secret authenticates the integration, not the workspace in the payload | Trusting an owned channel's payload team_id; scoping team-less ids by integration name (names are mutable) |
| 2026-09-28 | Adoption of raw-id `/v1` chat mappings is opt-in (`adopt-legacy-header-mappings`, default false) | A raw mapping may have been written for an OIDC principal under `http-policy=authenticated`; its origin is unrecorded | Adopting by default (reopens the cross-namespace reach); heuristics on the id's shape |
| 2026-09-28 | `/v1` refuses an OIDC principal carrying the reserved `openwebui:` prefix | Such a principal equals a header-derived identity | Namespacing OIDC principals too |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
| 2026-09-27 | Slack webhook rejects an event whose claimed workspace disagrees with the signing integration's declared teamId; a teamId-less unowned (DM) event is bound team-less, not to the payload team | Payload team_id was attacker-controllable in a validly-signed DM event → cross-tenant memory read (review residual #1) | Trusting the payload team when the integration declares none |
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id mappings are re-keyed only | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id mappings are re-keyed only (opt-in since 2026-09-28) | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Strip `SafeHttpClient.SENSITIVE_HEADERS` on every cross-origin redirect hop in the shared httpcalls client (`HttpClientModule`), and additionally disable redirect-following for a request whose header carries a resolved (reference) credential | Vert.x's redirect handler strips none of these headers cross-origin, so a literal credential written in the config leaked too; the hop-level strip covers every credential source, and the per-request disable is the stronger measure for resolved secrets | Disable redirects only for reference credentials (the first cut: missed literal credentials); strip only the three RFC headers (Vert.x does not even do that) |
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating | The parameter is now ignored unless `eddi.chat.url-token.enabled=true` (M6); the widget says so in the browser console | Set the property, or move to the `postMessage` hand-off | a136e09 |
| 2026-10-15 | A deployment behind a TLS-terminating proxy with neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` advertises an `http` MCP resource identifier | `force-https-scheme` now defaults to `false` (L3) | Set one of those two, or `force-https-scheme=true` | a136e09 |
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
| 2026-09-27 | Updating either of two pre-existing duplicate-named channel integrations of the same type now fails validation until one is renamed | New global per-type name uniqueness check on create/update (Finding C) — benign migration: rename one integration | Rename one of the clashing integrations, then update | fix/security-channel-identity |
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
