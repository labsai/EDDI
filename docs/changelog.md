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
| [October 2026](changelog/2026-10.md) | 197 | 867 KB |
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

## chore(release): attest SHA256SUMS with SLSA provenance (2026-10-24)

**Repo:** EDDI · **Branch:** `chore/release-provenance-attestation`

The OpenSSF Scorecard Signed-Releases check reported 6.6.0 as signed (the cosign bundle
`SHA256SUMS.sigstore.json`) but without provenance. The image job already attested its
digest with `actions/attest-build-provenance`; the release job, which publishes the
installer checksum list, did not attest anything.

- [`ci.yml`](../.github/workflows/ci.yml) — the `release` job gets `attestations: write` and a
  `Generate SLSA provenance attestation (SHA256SUMS)` step after the cosign signature, using the
  same pinned action as the image job. It runs before the GitHub Release is created, so a failed
  attestation publishes nothing.
- Verify a release with `gh attestation verify SHA256SUMS --repo labsai/EDDI`.
- Applies to tags cut after this merges. 6.6.0 stays without provenance; it cannot be attested
  retroactively.
- Open question: Scorecard's check may look for a `.intoto.jsonl` release asset rather than the
  attestation store. Confirm on the next tag; if it still warns, the fallback is the
  `slsa-framework/slsa-github-generator` generic generator, which emits that asset.

---

## 📝 docs(planning): evaluated generation — checks and repair for every step that produces something (2026-10-24)

**Repo:** EDDI (`docs/evaluated-generation-plan`)

### What changed and why

New planning document [`planning/evaluated-generation-plan.md`](../planning/evaluated-generation-plan.md). No code changes.

It started as an analysis of whether EDDI could host a production AI video-generation pipeline: about 150 model calls per job, best-of-N keyframes, multimodal judges, a repair ladder, a human cost gate, and ffmpeg assembly. The answer generalises beyond video. **Make → judge → repair** is the loop behind grounded RAG answers, extraction, text-to-SQL, output compliance, content production, group deliverables and media.

Verified against `main` at `5028af0a3`, EDDI already runs that loop in seven places:
- output guardrails (#1064, with the `classifier` type from #1112);
- format recovery (R5);
- the model cascade;
- TASK_FORCE VERIFY, which has no rework within a run;
- group task approval;
- single-agent HITL;
- team cadences.

The plan builds on them instead of adding an eighth:

| Item | What it adds |
| --- | --- |
| E1 | A `judge` output guardrail that answers with author-declared issue codes, so a reprompt says what to fix; `onExhausted: deliver-best` for quality checks, so the best attempt is delivered (still screened by every other guardrail) instead of a block message |
| E1c | A deterministic `expression` guardrail |
| E2 | Rework after a failed verdict, for humans and groups |
| E4 | Spend checks that see every attempt, and reservations for parallel calls |
| E5 | Checks and `forEach` on tool and HTTP steps |
| E6 | Media groundwork |
| E7 | Speech and image providers |
| E8 | Long jobs through self-continuation and parked operations |
| E9 | A fingerprinted DAG |
| E10 | A composition service outside core |

### Design decisions

- **Checks on meaning are output guardrails**, not a new block in `responseValidation`. Guardrails already run after it in every execution mode, with their own reprompt loop, cost metering and `failMode`.
- **`judge` is a sibling of `classifier`, not a mode of it.** It shares the classifier's model-call machinery: provider pass-through, timeouts, deadline, pricing and `promptTemplate` data. Its contract differs: a structured list of codes rather than "safe is a positive label".
- **Judge feedback uses a closed vocabulary.** `GuardrailService.correctiveMessage` never puts the user's or the model's text into a reprompt, so the judge returns only declared codes, and the producer is told only the designer's fix text for them.
- **The pipeline stays a straight line.** Loops live inside a step, and repairs that must reach an earlier step leave as actions.
- **ffmpeg, music and domain-specific logic stay outside core.**

---

## feat(chat-ui): conversation and reply ratings (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 2 of [`planning/conversation-rating-plan.md`](../planning/conversation-rating-plan.md) (§7 and §0.1 "User — UX rules")

### What changed and why

The Chat UI now lets the person chatting rate the conversation and, when the agent asks for it, single replies, against the owner endpoints PR 1 added. Everything is driven by the profile's `rating` object; without it the widget renders exactly as before.

- **Reply thumbs** ([`StepRating.tsx`](../ui/chat/src/components/StepRating.tsx)) under the last agent bubble of each step — hover/focus on desktop, always on touch, `aria-pressed`, 44px targets on touch. Thumbs down rates at once, then offers optional reason tags and a comment.
- **Rating card** ([`RatingCard.tsx`](../ui/chat/src/components/RatingCard.tsx)), a sibling of `PausedCard`: stars as a radio group (roving focus) or thumbs; offered on end (`askOnEnd`) and/or after `askAfterTurns` replies, at most once per conversation, never to somebody who already rated, never again once closed (localStorage, guarded); always available from a header entry. One tap rates, tags/comment follow (comment per `askBelowScore`), then a thanks state naming who receives the feedback; editable and removable afterwards; never blocks the composer.
- **State** ([`useRatings.ts`](../ui/chat/src/hooks/useRatings.ts)): restored via `GET /agents/{id}/rating` when a conversation opens; optimistic updates with rollback and a short notice; every backend error code mapped to translated copy ([`rating-api.ts`](../ui/chat/src/api/rating-api.ts)); a rerun clears the local thumb of that step.
- **Steps** ([`rating-steps.ts`](../ui/chat/src/rating-steps.ts)): reply ratings are keyed by step. Full conversation reads now stamp `stepIndex` on their messages (`stepsToMessages(…, fullHistory)`, `processSnapshot({ fullHistory })`); live turns are counted from the nearest stamped message.
- **Demo mode** gets an in-memory rating backend and a profile asking for every control. i18n for en/de/fr/es; styles for both themes and phones.
- Docs: Chat UI section in [`conversation-ratings.md`](conversation-ratings.md); [`ui/chat/AGENTS.md`](../ui/chat/AGENTS.md) structure and the rating error envelope.

### Design decisions

- The rating endpoints' error body is `{error: CODE, message}`, not the attachments' `{error, code}`; `rating-api.ts` parses its own and never shows the server's message.
- One thumb per **step**, under the step's last bubble, because the server stores one reply rating per step.
- Stars use radio semantics for screen readers, but arrow keys only move focus and Enter/Space rates: with "one tap rates", arrow-selects-and-submits would send a rating per key press.
- A rerun clears the thumb locally only, as plan R8 says; the stored rating reappears after a reload (documented limitation).

### Testing

`npm run typecheck`, `npm run lint` and the full vitest suite (34 files, 564 tests, 67 of them new) pass; key logic was mutation-checked. Vitest needs Node ≥ 22.15 (jsdom 30's undici): the Maven-downloaded `ui/node/v24.*` works.

---

## feat(ratings): conversation ratings — backend (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 1 of the plan in [`planning/conversation-rating-plan.md`](../planning/conversation-rating-plan.md)

### What changed and why

People chatting with an agent can now rate the conversation and, if the agent asks for it, single agent replies; the people running the agent can list, summarize, export and triage those ratings. Off unless the agent designer adds `ratingConfig` with `"enabled": true` (checked per agent version the conversation runs on, cached per version in `ConversationRatingPolicy` like `ConversationReviewPolicy`).

- **Config:** `AgentConfiguration.ratingConfig` (scale `THUMBS|FIVE_STAR`, conversation and reply ratings, comment rules, reason tags, rating window), validated on agent create/update/duplicate (`RatingConfigValidator`), and a `rating` subset on `GET /agents/{agentId}/profile` so chat clients need no extra call.
- **Store:** `IConversationRatingStore` with MongoDB ([`ConversationRatingStore`](../src/main/java/ai/labs/eddi/engine/ratings/mongo/ConversationRatingStore.java)) and PostgreSQL ([`PostgresConversationRatingStore`](../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationRatingStore.java)) implementations selected in `DataStoreProducers`. Key `(conversationId, stepIndex)`, conversation rating at `-1`; atomic upsert keeping `id`/`createdAt`; summaries aggregated in the database; indexes for agent+time, agent+version, user (GDPR) and the needs-attention count.
- **Owner REST** (`IRestConversationRating`): `PUT|GET|DELETE /agents/{conversationId}/rating` and `PUT|DELETE /agents/{conversationId}/steps/{stepIndex}/rating`, with stable error codes (`RATING_DISABLED`, `RATING_WINDOW_CLOSED`, `INVALID_STEP`, `INVALID_SCORE`, `INVALID_TAG`, `COMMENT_NOT_ALLOWED`, `COMMENT_TOO_LONG`, `NOT_CONVERSATION_OWNER`, `CONVERSATION_NOT_FOUND`).
- **Maintainer REST** (`IRestConversationRatingAdmin`, `eddi-admin`/`eddi-editor`): `GET /ratings`, `/ratings/summary`, `/ratings/export` (CSV, 100k cap, formula-injection escaping), and the triage loop of plan §0.1: `GET /ratings/attention-count`, `PATCH /ratings/{ratingId}`, `POST /ratings/review`.
- **GDPR:** `ConversationRatingGdprParticipant` joins erasure (deletes the user's ratings, clears them as reviewer) and export; processing restriction blocks new ratings.
- **Metrics:** `eddi_conversation_ratings_total`, `eddi_conversation_ratings_normalized`, `eddi_conversation_ratings_withdrawn_total`, charted in a new "Conversation Ratings" row of the Full Metrics Reference dashboard.
- **Docs:** new [`conversation-ratings.md`](conversation-ratings.md), `ratingConfig` section in [`agent-config-authoring.md`](agent-config-authoring.md), [`gdpr-compliance.md`](gdpr-compliance.md), [`metrics.md`](metrics.md).

### Design decisions

- **Only the owner rates.** `requireConversationOwner` admits administrators too; the endpoint additionally requires `OwnershipValidator.isOwner`, so an admin can read and withdraw but never write a rating in somebody's name.
- **User ids for administrators only** — decided on `isAdmin()`, not `seesEverything()`: without workspace enforcement every editor "sees everything", and the plan's R3 is about admins.
- **Scoping (R6):** a caller who does not see everything must pass `agentId` and hold `EDIT`; `PATCH` checks `EDIT` on the rating's own agent.
- **`canOpenTranscript`** applies the existing conversation-read rule per row (owner, admin, or conversation review) and is false for purged conversations — one descriptor read per distinct conversation on the page (≤ 100).
- **Triage is hidden from the rater:** owner responses strip status, reviewer and note. A re-rating resets triage to `NEW` and clears the note. `PATCH` is conditional on the rating's `updatedAt`, so a maintainer cannot mark reviewed a rating that changed after they read it (409 `RATING_CHANGED`). The reviewer always comes from the principal.
- **`minScore`/`maxScore` filter the normalized score** (0..1), so one filter means the same on every scale. Added `sort=newest|lowest`, `reviewStatus` and `needsAttention` filters.
- Admin endpoints share one `@BeanParam` (`RatingQueryParams`) so the five filtered endpoints cannot drift.

### Deviations from the plan

- A reply rating records the conversation's agent version at rating time: conversation memory stores no version per step (plan §10 assumed one).
- The per-reply excerpt for callers who may read the transcript (§6) is not returned; clients with `canOpenTranscript` open the conversation instead.
- GDPR: implemented as an `IGdprParticipant` (erase + export), which is the SPI for stores; `UserErasureParticipant` is for stopping in-flight work and has nothing to stop here.
- No separate `(conversationId)` index: the unique key's prefix serves it.

### What's next

The MCP rating tools ship with this backend. Next: the Chat UI and the Manager Feedback page (badge on `attention-count`) with the Platform Operator's read access, then push alerts. The store ITs (`MongoConversationRatingStoreIT`, `PostgresConversationRatingStoreIT`) need Docker.

---

## ✨ feat(mail): email notifications for pending approvals — email Phase 1 (2026-10-24)

**Repo:** EDDI (`feat/email-hitl-notifications`, on top of `feat/email-mail-core`)

Phase 1 of [the email plan](../planning/email-channel-plan.md) (§16 "Notifications", the outbound half of §12).

### Socket guard: an operator policy

- **`SafeSocketConnector.resolveOperator`** (`Policy.OPERATOR`) for a host the operator set in
  `application.properties` — today only the system mailer's relay. Private and loopback addresses, IP literals,
  single-label names and any port (25 included) are allowed, and `eddi.email.allowed-hosts` (which confines
  authors) is not consulted. Still resolved once and pinned, still no link-local or cloud-metadata address, TLS
  still verified. `TlsMode.PLAINTEXT` exists only on an operator endpoint: the `Endpoint` constructor refuses it
  for an author one. `resolve` (the author policy) is unchanged.

### Connections: mail origins on `baseUrlAllowlist`

- A connection's `baseUrlAllowlist` now accepts `smtp://`, `smtps://`, `imap://` and `imaps://` origins
  (`ConnectionConfiguration.requireCanonicalAllowlistOrigin`), so `resolveAccessToken` (XOAUTH2) and the new
  `resolveBasicLogin` (username + vault-backed password for SMTP/IMAP login) can check a mail server against it.
  The ports a mail scheme implies fold away (`smtps` 465, `smtp` 587, `imaps` 993, `imap` 143); `smtp://relay:25`
  stays a distinct origin. An https entry for the same host does not cover a mail origin. The credential-endpoint
  allowlist and the public base URL stay http(s) only. The Manager's `connection-validation.ts` mirror accepts the
  same schemes, with the `originScheme` message updated in all 11 locales.

### The system mailer

- **`SystemMailer`** (`engine/mail`): the deployment's one SMTP sender, configured by the operator under
  `eddi.mail.smtp.*` (host, port, `tls` = `starttls` | `implicit` | `none`, `from`, `from-name`, and at most one
  credential source: `connection` — `BASIC` or `OAUTH2_CLIENT_CREDENTIALS`/XOAUTH2 — or `username` + `password`).
  Unset host = disabled, one INFO line at startup; an unusable configuration disables it with one ERROR line and
  never falls back to something weaker. Sockets come from `SafeSocketConnector.resolveOperator`. STARTTLS is
  required, not opportunistic; `tls=none` needs `eddi.mail.smtp.allow-plaintext=true` and never carries a
  credential. Failures are classified (`MailSendException.Category`), permanent only for a 5xx refusal.
- **GreenMail 2.1.14** (Apache-2.0, test scope; its aggregate `org.eclipse.angus:jakarta.mail` and JUnit 4 are
  excluded) runs implicit TLS, plain SMTP and AUTH in `SystemMailerTest`; a small in-test STARTTLS server covers the
  upgrade GreenMail does not speak, and proves AUTH (PLAIN and XOAUTH2) only ever crosses the wire after it. The
  certificate is a throwaway made with the JDK's `keytool`; the production TLS path, with certificate and host-name
  checks, is what accepts it.

### The message ledger (outbound)

- **`IEmailMessageLedger`** (`integrations/email/ledger`) with `MongoEmailMessageLedger` (`email_message_ledger`
  collection) and `PostgresEmailMessageLedger` (`email_message_ledger` table, created on first use), selected by
  `eddi.datastore.type` in `DataStoreProducers`. One row per message: integration (`system` for system mail),
  kind (`NOTIFY`; `REPLY` later), direction, RFC Message-ID, conversation, `sourceRef` (what the message is rebuilt
  from — the pause id for a notification), idempotency key, recipient, state `PENDING → SENT | FAILED | SUPPRESSED`,
  attempts, `nextAttemptAt`, claim token, provider response, last error, created/updated/expires. **No body,
  subject or MIME** — a retry re-renders from the source. Two unique keys (idempotency key;
  `(integrationId, direction, messageId)`) make an insert a cluster-wide claim. `claimDue` leases rows by a
  conditional update (`findOneAndUpdate` per row on Mongo; `UPDATE … FOR UPDATE SKIP LOCKED … RETURNING` on
  Postgres), and every state change is conditional on the claim token, so a taken-over claim cannot overwrite the
  new owner's result.
- `MongoEmailMessageLedgerIT` / `PostgresEmailMessageLedgerIT` run one shared contract against real databases
  (Testcontainers): duplicate keys, eight concurrent inserts of one notification (one winner), two sweepers claiming
  20 rows at once (no overlap), lease expiry and stale claims, reschedule, retention, erasure by recipient, and the
  stored field set (no body field exists).

### HITL email notifications

- **Agent opt-in** `hitlConfig.notify.email.recipients` (`HitlNotifyConfig`): 1–20 plain ASCII addresses, validated
  at save and import time (`HitlConfigValidation.validateNotify` — empty, over 20, malformed, display-name,
  non-ASCII and duplicate-after-normalisation entries refused with the position, never the address). Configs without
  the field are unchanged.
- **The hook:** `HitlPauseCommittedEvent(conversationId, pauseId)`, fired asynchronously by
  `ConversationService.firePauseCommitted` at the three places a regular pause is committed — the say turn
  (`ConversationStepRunner`, after the state CAS and the timeout schedule), the `CONVERSATION_START` turn, and a
  resume that pauses again (`ConversationHitlService`). Not for a pause restored after a failed resume, nor on a CAS
  miss. Group discussions are not covered yet.
- **`HitlEmailNotifier`** (`integrations/email/notify`): re-reads the pause from the store (state and `pauseId` must
  still match), inserts one ledger row per recipient under a digest key of kind/conversation/pause/recipient, and
  sends at once for the rows it won. A `@Scheduled(identity = "email-ledger-sweeper")` sweep retries due rows with
  exponential backoff (`eddi.email.outbound.retry-backoff`, capped at 1 h) up to `eddi.email.outbound.max-attempts`,
  removes expired rows (`eddi.email.ledger-retention`) and refreshes the `eddi.email.ledger.pending` gauge. A
  decided pause ends `SUPPRESSED`; a 5xx fails at once.
- **Content** (`HitlNotificationContent`): agent name, pause type, reason, start, deadline + policy, conversation id,
  and for a tool-call pause the same redacted, 300-char-truncated argument lines as the Slack card — extracted from
  `SlackHitlSupport` into the shared `HitlToolCallPreview`. No conversation content. Subject
  `[EDDI] Approval needed: <agent name>`, designer text flattened to one line. The link is
  `eddi.connections.public-base-url` + `/manage/approvals`, no token; without a base URL the page is named, not
  linked.
- **Metrics** `eddi.email.outbound{kind,outcome}` and `eddi.email.ledger.pending`, on the Full Metrics dashboard's
  Human-in-the-Loop row and in `metrics.md`. **Audit** entry `email.notify` per sent/failed notification with the
  recipient as a SHA-256 hash plus domain (the audit ledger is write-once). **GDPR**: `EmailLedgerGdprParticipant`
  (`emailLedger`) erases/exports rows by the user id's address and the directory's recorded email;
  `IGdprParticipant.order()` makes the user directory run last so the address is still readable.

### Recipient domain policy and flood brake (review follow-up)

- **`eddi.mail.notify.allowed-recipient-domains`** (`NotifyRecipientPolicy`, `engine/mail`): exact domains, `*.domain`
  for subdomains, `*` for any; **unset = the domain of `eddi.mail.smtp.from` and its subdomains**. Recipients are set by
  anyone with EDIT on an agent while the mail leaves through the operator's relay under the operator's sender, so
  without a bound the relay was an open mailer for agent authors. Enforced at send time on every attempt (the row ends
  `SUPPRESSED`, `RECIPIENT_NOT_ALLOWED`, `outcome=not_allowed`) and at save time in `HitlConfigValidation` — refused
  when a mailer is configured, accepted with a WARN when not (read through `ConfigProvider`, since the validation is
  static). A malformed entry fails closed: nobody is mailed, ERROR at startup.
- **`eddi.mail.notify.max-per-recipient-per-hour`** (default 30): `IEmailMessageLedger.countCreatedSince(recipient, kind,
  since)` counts the recipient's non-suppressed `NOTIFY` rows of the last hour across the cluster (new index
  `(recipient, kind, createdAt)` on both stores); over the limit the row is `SUPPRESSED` (`RATE_LIMITED`,
  `outcome=rate_limited`) and each instance logs one WARN per recipient per hour, naming it by hash. Suppressed rows do
  not count, so the window empties once the flood stops. Count and insert are two steps, so concurrent pods can
  overshoot by a few.
- Docs (`hitl.md`, `configuration-reference.md`, `metrics.md`), the Manager hint and the operator prompt say so;
  operator revision stays 37 (one PR) with the new fingerprint.

### Manager and the Platform Operator

- The agent's **Human-in-the-Loop** settings gain "Email people when this agent waits for approval" with a
  recipients field (`agent-config-sections.tsx`, parser in `lib/hitl-notify.ts` mirroring the save-time rules: 1–20,
  plain ASCII, no duplicates). As with the approval timeout, an unsavable list never enters the draft; the field shows
  what is wrong. Eight new `agentDetail.hitlNotify*` keys in all 11 locales.
- **Operator prompt** (`system-prompt.ts`): how email notifications are set up — the operator's
  `eddi.mail.smtp.*`, the agent's `hitlConfig.notify.email.recipients` in the Manager (the operator cannot write
  `hitlConfig`), what the mail contains, why there is no "all approvers" switch. No new endpoint, so `tool-scopes.ts`
  is unchanged. **Operator revision 36 → 37.**
- Docs: [`hitl.md` → Email notifications](hitl.md#email-notifications) (with a sample config),
  [`configuration-reference.md` → Email](configuration-reference.md#email), [`metrics.md`](metrics.md#hitl-metrics),
  [`security.md` → the operator policy](security.md#the-operator-policy--the-system-mailers-relay),
  [`connections.md`](connections.md), [`gdpr-compliance.md`](gdpr-compliance.md),
  [`compliance-data-flow.md`](compliance-data-flow.md).

### For Phase 2 and 3

- The ledger API is the one they extend: add inbound states and a stranded-turn query to `IEmailMessageLedger`,
  `Kind.REPLY` rows get their own worker (`claimDue` takes a kind), `sourceRef` carries the conversation step a
  reply is rebuilt from. The unique `(integrationId, direction, messageId)` key is already the inbound dedup key.
- The SMTP client is `SystemMailer` for operator relays only; a channel's author-configured SMTP must use
  `SafeSocketConnector.resolve` (the author policy), not `resolveOperator`.
- Group discussions (`AWAITING_APPROVAL`) are not notified yet; `GroupHitlCoordinator` commits group pauses in three
  places and would need its own event.

---

## ✨ feat(mail): mail core, socket guard and bare OAuth tokens — email Phase 0 + 0b (2026-10-24)

**Repo:** EDDI (`feat/email-mail-core`)

The first slice of [the email plan](../planning/email-channel-plan.md) (Phase 0 and 0b): a pure library under
`engine/mail` that later phases (notifications, trigger mode, conversation mode) and the mail RAG source
([`rag-ingestion-source-types-plan.md`](../planning/rag-ingestion-source-types-plan.md) §8) build on. No channel,
no transport, no conversation code — nothing here is reachable from an agent yet.

### What changed and why

- **Angus Mail** (`org.eclipse.angus:angus-mail` 2.0.5, `jakarta.mail:jakarta.mail-api` 2.1.5 — the versions the
  Quarkus BOM manages, so one copy on the classpath; EPL-2.0 / GPL-2.0-with-CPE / EDL-1.0) for MIME grammar.
- **`MimeMessageParser`**: raw RFC 5322 bytes → `InboundMail`. Every limit (`MailLimits`: raw bytes, header bytes,
  nesting depth, part count, attachments, per-attachment bytes, output characters) is enforced before the work it
  bounds — multipart delimiters are counted in the raw bytes before Jakarta Mail materialises a part, so a few KB
  declaring thousands of parts is refused, not allocated, and a base64-encoded multipart (which would hide its
  delimiters) is skipped. `text/plain` preferred, HTML converted as fallback. Broken and unknown charsets, RFC 2047
  words (split multi-byte, unknown charset), unparseable dates and invalid addresses become `MailAnomaly` values,
  never exceptions. A message without a Message-ID gets `sha256:<hex of raw bytes>`, flagged `syntheticMessageId`.
  Executables (by name, type and magic bytes) never carry content; archives are flagged, never expanded;
  `message/rfc822` is never walked into. `multipart/report` DSNs are parsed into a `DeliveryReport` (recipient,
  action, status, original Message-ID) for later bounce suppression.
- **`AddressNormalizer`** (lower-case, IDN→ASCII, no dot/`+tag` folding, no domain literals or numeric TLDs),
  **`QuoteStripper`** (trailing `>` blocks with en/de/fr/es/it/nl attributions; Outlook header blocks in en/de/fr;
  Gmail `gmail_quote`, Apple/Thunderbird `blockquote type=cite` + `moz-cite-prefix`, Outlook web markers —
  interleaved inline replies, forwards and quote-only messages are kept whole), **`AutoReplyDetector`** (RFC 3834,
  `X-Autoreply`/`X-Autorespond`, `Precedence`, `List-*`, null/daemon return paths, reports),
  **`PlainTextMailRenderer`** (`text/plain` UTF-8 `format=flowed`, threading headers with a capped References chain,
  `Re:` without stacking, `Auto-Submitted`, `X-Auto-Response-Suppress: All`, random `<eddi.<uuid>@domain>` ids, and
  a refusal of any header value containing CR/LF or another control character).
- **`HiddenContentFilter`**, run by `HtmlToMarkdownConverter`, in two modes. **Page mode** (`convert()`, every
  existing caller — crawler, file upload, `WebScraperTool`) is conservative: only HTML comments, text of 1px or
  less, transparent text, and text the colour of a background set explicitly on it or an ancestor are dropped.
  Accordions, `hidden` tab panels, `opacity:0` fade-ins, off-screen skip links and `sr-only` text — what pages hide
  until script reveals it, and what a knowledge base wants — are kept. **Mail mode** (`convertMail`) is strict,
  since mail runs no script: also `display:none`, opacity ~0, `mso-hide`, zero-size + overflow hidden, off-screen,
  `clip`, `scale(0)`, `hidden`, inherited `visibility:hidden`, `display:none`/`visibility:hidden` rules in
  `<style>` outside `@media` (`HiddenStylesheetRules`), and an assumed white page with black text. `convertMail`
  also skips the `main`/`article` scoping and the `<title>`.
- **Linear on hostile input.** Style blocks are read by one forward `indexOf` scan (no backtracking regex) and
  matched in one pass against an index of rules; nothing is truncated, so a hiding rule after megabytes of junk is
  still applied. Where exact matching would cost more, it over-approximates (rightmost compound selector only; an
  element more than 64 hiding rules could apply to counts as hidden), which can only remove more. The quote
  stripper's Outlook-header search computes subtree text sizes bottom-up and its top-level searches skip matched
  subtrees. jsoup 1.23.2 itself caps tree depth at 512 (deeper elements become siblings).
- **`ALTERNATIVES_DIVERGE`**: when a `multipart/alternative` has both parts, the plain text (what the agent reads) is
  compared with the HTML as rendered, hidden content removed (what a reviewer sees), by word sets in both
  directions; a substantial difference adds the anomaly. `text/plain` stays the preference.
- **`SafeSocketConnector`** (`engine/net`): the socket-level SSRF guard the RAG plan §3.10 proposed. DNS names only
  (every IP-literal spelling refused), IMAP 993/143-STARTTLS and SMTP 465/587-STARTTLS only, optional
  `eddi.email.allowed-hosts`, resolved once with every address checked by `UrlValidationUtils.isPrivateAddress`,
  pinned to the first address (`PinnedSocket` ignores a library's second lookup), TLS always verified, timeouts.
  `angusProperties(endpoint)` wires Angus Mail to it with `socketFactory.fallback=false`; a loopback smoke test
  proves Angus connects through it, requires STARTTLS before any credential, and does not fall back.
- **`ConnectionResolver.resolveAccessToken`**: the bare access token for `XOAUTH2`, OAuth connections only
  (`STATIC`, `BASIC`, `CALLER_SUPPLIED` → `UNSUPPORTED_PLACEMENT`), with the allowlist, `PER_USER`/`VERIFIED` rules
  and the shared token supplier (refresh, caching) unchanged.

### For the next phases

- `InboundMail.authentication()` is `NOT_EVALUATED` until the DKIM verifier attaches a verdict with
  `withAuthentication`; only `ALIGNED_PASS` authenticates. The verifier must also treat the `MULTIPLE_FROM` and
  `DUPLICATE_SINGLETON_HEADER` anomalies as unauthenticated. Raw bytes are not kept in `InboundMail`; the verifier
  gets them from the transport.
- `baseUrlAllowlist` holds http(s) origins only, so `resolveAccessToken` refuses an `imaps://` target today (fails
  closed). The IMAP phase must teach the connection model (and the Manager's mirror in
  `connection-validation.ts`) mail origins.
- Private-address mail servers (on-premises Exchange) are refused, with no override yet.

**Files:** [`engine/mail/`](../src/main/java/ai/labs/eddi/engine/mail/),
[`SafeSocketConnector.java`](../src/main/java/ai/labs/eddi/engine/net/SafeSocketConnector.java),
[`HiddenContentFilter.java`](../src/main/java/ai/labs/eddi/modules/ingestion/HiddenContentFilter.java),
[`HiddenStylesheetRules.java`](../src/main/java/ai/labs/eddi/modules/ingestion/HiddenStylesheetRules.java),
[`ConnectionResolver.java`](../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java),
[`security.md`](security.md#mail-sockets--safesocketconnector),
[`configuration-reference.md`](configuration-reference.md#email), [`rag.md`](rag.md), fixtures under
[`src/test/resources/mail/`](../src/test/resources/mail/).

---

## ✨ feat(llm): `expression` guardrail — a deterministic check over a JSON answer (2026-10-24)

**Repo:** EDDI (`feat/llm-expression-guardrail`, from `main` at `5028af0a3`) — item E1c of the evaluated-generation plan. Independent of the `judge` guardrail (#1121, `feat/llm-judge-guardrail`): it builds only on what `main` has (`ContentGuardrail`, `GuardrailVerdict.violation(reason, hint)`, `GuardrailUndecidedException`, `failMode`).

### What changed and why

A well-formed answer can still be wrong: an invoice whose `total` is not the sum of its lines passes `responseSchema` and `json-object`. `expression` (output only) parses the answer as JSON and evaluates an author-written boolean expression over it, and reprompts with author-written feedback — the deterministic sibling of the `judge`.

```json
{ "type": "expression", "action": "reprompt", "maxRetries": 1,
  "parameters": { "expr": "sum(lines[*].amount) == total", "issue": "LINE_SUM",
                  "fix": "Make total equal the sum of the line amounts, to the cent." } }
```

- **`SafeJsonExpression`** (`ai.labs.eddi.utils`, new) — a small recursive-descent language in the style of `CalculatorTool.SafeMathParser`: number/string/`true`/`false`/`null` literals; paths (`invoice.total`, `lines[0].amount`, `invoice['due-date']`, `$` for the answer itself) with a `[*]` wildcard that yields a list; `+ - * / %` on `BigDecimal` (34 significant digits, `0.1 + 0.2 == 0.3` is true); `== != < <= > >=` (not chainable); `&& || !` with short-circuit; parentheses; nine functions — `sum`, `count`, `min`, `max`, `len`, `abs`, `round(x, n)` (half-up), `exists(path)`, `approx(a, b, tolerance)`. No script engine, no expression library, no reflection, no Qute; an unknown function is a compile-time error. JDK-only and Java 17 syntax so ClusterFuzzLite can compile a vendored copy.
- **Null semantics:** a missing path and a JSON `null` are both `null`; `== null` / `!= null` test it; any other comparison with `null` is `false`; arithmetic, logic or a function on `null`, a type mismatch, division by zero, an empty `min`/`max` and an out-of-range number are evaluation errors — *could not decide*, never `false`. `exists(path)` tells a present key holding `null` from a missing one.
- **Bounds:** expression ≤ 2000 characters, nesting ≤ 32, ≤ 500 parts, ≤ 32 segments per path (all refused at compile time with a position, never a `StackOverflowError`); ≤ 10,000 values per path, ≤ 200,000 steps per evaluation; numbers beyond 1000 digits or a ±1000 scale refused rather than materialised.
- **`ExpressionGuardrail`** (`modules/llm/guardrails/impl`, new bean, picked up by `GuardrailService`'s type index) — parameters `expr`, `issue` (`[A-Z][A-Z0-9_]{0,39}`), `fix` (≤ 1000 characters), all required and checked in `validate()` at deploy (`parameter 'expr' is not valid at position 21: '=' is not an operator; compare with '=='`). Compiled expressions sit in a bounded Caffeine cache (512, refusals cached too). The answer is read with `ModelOutputParser` (fence and prose tolerant) over its own exact mapper: decimals as `BigDecimal`, integers as `BigInteger`, duplicate keys refused. Verdicts: `true` → allow; `false` → `VIOLATION` `expression_failed:<ISSUE>` with a hint that is EDDI's fixed frame (the judge guardrail's wording) around the author's `fix` only; not JSON / > 262,144 characters / duplicate key → `GuardrailUndecidedException` `expression_unparseable`; evaluation error or a non-boolean result → `expression_undecided:<cause>` (the evaluator's own codes). `failMode` decides as for every type.
- **Fuzzing:** `SafeJsonExpressionFuzzTest` (Jazzer `@FuzzTest` over expressions and over JSON data, plus seeded random sweeps that run in every unit-test run) and a ClusterFuzzLite target `SafeJsonExpressionFuzzer`; `.clusterfuzzlite/SafeJsonExpression.java` is the vendored copy, and the `Vendored Fuzz Sources In Sync` guard knows the fourth file.
- **Docs:** [langchain.md](langchain.md#the-expression-type-a-deterministic-check-over-a-json-answer) (grammar, semantics, bounds, the invoice example and the exact corrective message), [security.md](security.md) (sandboxed evaluator, closed vocabulary, fuzz targets), [security-review.md](security-review.md).

- **End-to-end proof:** `ExpressionGuardrailIT` runs a real agent against a WireMock fake OpenAI endpoint: pass (also from a code fence), fail then repair (the second request carries the fixed frame around the author's `fix` and no computed value), exhausted reprompt and `block`/`warn` actions, non-JSON, duplicate-key and null-operand answers under both `failMode`s, exact decimal arithmetic (`0.1 + 0.2 == 0.3`, a one-cent difference caught), and deploy refusal for a syntax error, an over-long or over-deep expression, sandbox escapes and a bad issue code or fix.

### Design decisions

- **A new evaluator, not `ExpressionProvider`** (plan §9.1): that is the NLP expression grammar, with no arithmetic or paths into JSON. `PathNavigator` has only `+`/`-` on path values, no comparisons, logic or aggregates.
- **Could not decide is the safe default for anything ambiguous.** A missing amount inside `sum(...)` is not skipped, comparing a number with a string is not `false`: both are undecided, so a `reprompt`/`block` guardrail withholds the answer (fail closed) unless the author set `failMode: open`.
- **Closed vocabulary:** nothing read or computed from the answer — not the sum, not the total — reaches the corrective message. Only the issue code (author-declared, pattern-checked) reaches the audit reason.
- **Duplicate keys are unparseable**, so the check and a downstream reader of `convertToObject` cannot see two different totals.
- **No new meter**; `eddi.guardrail.triggered` counts the action as for every type.

### Manager and operator

- The guardrail editor offers `expression` on the output side (`guardrails.ts`: type list, phases, no-rewrite set, list-row summary `ISSUE · expr`, type text). Its sheet has fields for `expr`, `issue` and `fix`, and `expressionProblems()` mirrors `validate()`'s refusals (required, length caps, the issue-code pattern) as warnings; the expression's syntax is left to the server, which names the position at deploy. 16 new `llmEditor.grExpr*`/`grTypeExpression*` keys in all 11 locales. Tests: `guardrails-expression.test.ts` and an `expression` block in `task-guardrails-section.test.tsx`.
- The Platform Operator's prompt explains the type (parameters, the language, null rules, the reasons, closed-vocabulary feedback, deploy refusals with a position); `operator-revision.json` 36 → 37. #1121 also takes 37, so whichever merges second re-bumps.

### Next

- When #1121 lands, `expression` can use `onExhausted: deliver-best` like any reprompting type, and its `HINT_HEADER`/`HINT_FOOTER` can be shared with the judge's (same wording today).

---

## ✨ feat(llm): `judge` guardrail and `onExhausted: deliver-best` — evaluated generation E1a + E1b (2026-10-24)

**Repo:** EDDI (`feat/llm-judge-guardrail`) — item E1 (E1a and E1b; not E1c) of the evaluated-generation plan.

### What changed and why

A well-formed but wrong answer passed every output guardrail EDDI had: the built-ins check shape and patterns, the `classifier` checks safety. E1a adds a check on **meaning** whose feedback says what to fix; E1b lets a quality check that cannot be satisfied deliver its best attempt instead of a block message.

- **`GuardrailModelClient`** (`modules/llm/guardrails/impl`, package-private) — the classifier's model-call machinery, extracted rather than forked: `ChatModelRegistry` provider build with parameter pass-through (vault and `${vars}` resolved by the registry), `timeoutMs` capped at 60 s and bounded by the turn deadline, the virtual-thread executor, `promptTemplate` rendering with the screened text as data only (`{input}`, `{output}`, `{context}`), `includeRetrievedContext`, pricing from `inputPricePer1M`/`outputPricePer1M`, the unavailable/unparseable mapping (provider messages never propagated) and the per-call timer. `ClassifierGuardrail` now delegates to it; its behaviour, reasons, metric and test surface are unchanged (all 54 of its tests pass untouched). Each type passes its timer as `() -> Timer.builder(METRIC_DURATION)` so `MonitoringSeries` still finds the meter where it is named.
- **`JudgeGuardrail`** (`judge`, output only, `screensHistory()` false) — parameters `provider`, `promptTemplate` and `issues` (a JSON object of issue code → author fix text; 1–20 codes matching `[A-Z][A-Z0-9_]{0,39}`, fix text non-blank and ≤ 1000 chars, duplicate codes refused), plus the shared ones and a per-guardrail `jsonResponseFormat` override. After the rendered template EDDI appends a fixed instruction listing the codes and the reply shape `{"issues": [...]}`. The request carries a JSON response format per request through `JsonResponseFormatPolicy` — with the codes as an `enum` schema where the provider enforces one — never baked into the cached model. The reply is read with `ModelOutputParser` (fence, prose around the object), codes matched case-insensitively. Only a parsed empty list passes; any other shape or an undeclared code is `judge_unparseable`, an unreachable or slow judge `judge_unavailable` — `failMode` applies.
- **Closed-vocabulary feedback.** A finding is a `VIOLATION` with reason `judge_failed:<CODE>[,<CODE>…]` (declaration order) and a reprompt hint made of EDDI's frame and the author's fix texts for those codes only. `GuardrailService.correctiveMessage` is untouched, so its invariant — never the user's or the model's text — now also holds for judge output.
- **`GuardrailVerdict.issues`** — a sixth record component (the 5-argument constructor stays), plus `GuardrailVerdict.issues(...)` and `severity()` (0 = nothing found, the code count for a grading guardrail, 1 for any other finding).
- **`GuardrailConfig.onExhausted`** — `block` (default; also when unset) or `deliver-best`, plus `deliversBest()` (not a bean getter, never serialized). In `GuardrailService.runOutput`, a `deliver-best` reprompt guardrail records every attempt it rates; when it may not reprompt again (retries spent, no reprompter, or a reprompt that cannot start) the attempt with the lowest severity is delivered — ties to the later attempt, so a binary guardrail delivers the last — with a `warn` outcome, reason `<reason>:delivered_best`, `retryCount` = reprompts sent. That attempt is then **re-screened** by the output guardrails after the exhausted one, with no further reprompts — the ones before it already screened that attempt in the pass that rated it, so each guardrail sees it once and a model-backed one is called once: `redact` still rewrites, `warn` still records, `block` and `reprompt` findings block. The re-screen's outcomes are held back until it ends, so the `:delivered_best` warn is recorded only for an attempt that was actually delivered; a blocked one leaves the re-screen's records and the block, each counted once. A guardrail that could not decide is not "exhausted": its `failMode` applies as before. Because this lives in `runOutput`, it covers every mode output guardrails run in — plain, streaming (buffered), tool loop, cascade, HITL resume — and audit records, the ledger, SSE `guardrail_triggered` and `eddi.guardrail.triggered` stay on the one `emit` path.
- **Deploy time.** `GuardrailService.validate` refuses an unknown `onExhausted`, `deliver-best` on any action but `reprompt`, and `deliver-best` on input guardrails; `JudgeGuardrail.validate` refuses a missing `provider`/`promptTemplate`/`issues` and a malformed `issues`. New `GuardrailService.warnings` / `ContentGuardrail.warnings` (default none) are logged by `LlmTask.validateGuardrails`: `deliver-best` on a `classifier`, an unpriced judge, and a judge on the task's own provider and model (self-judging bias) — risks the plan's §8 lists.
- **Metric:** timer `eddi.guardrail.judge.duration{provider, outcome}` (`pass`, `fail`, `unparseable`, `unavailable`, `timeout`), mirroring the classifier's, with a panel on the Full Metrics dashboard next to it; `eddi.guardrail.triggered` counts the actions as before.
- **Docs:** [langchain.md](langchain.md#the-judge-type-grading-the-answer-with-issue-codes) (type row, `onExhausted` field row, the `judge` subsection, [When the retries run out](langchain.md#when-the-retries-run-out-onexhausted)), [security.md](security.md) (closed vocabulary; a quality check never overrides a safety check), [metrics.md](metrics.md).

### Design decisions

- **Deviations from the plan, and why.** (1) `onExhausted: "block"` is accepted on any action (it is the default and changes nothing); only `deliver-best` is refused outside `reprompt`, and also on input guardrails, where a reprompt blocks. (2) `deliver-best` also applies when no reprompt can run at all (no reprompter, out of time, open circuit), not only when `maxRetries` is spent — in each case the guardrail has nothing further to try. (3) An attempt the guardrail *passed* but another guardrail later sent back is still a candidate with severity 0; if chosen, the re-screen decides. (4) A per-guardrail `jsonResponseFormat` parameter was added as the escape hatch for providers the matrix does not know, mirroring the task setting. (5) The judge's default `timeoutMs` is 5000, not the classifier's 3000: a grading LLM is slower than a safety classifier. (6) The fix texts are also listed in the judge's instruction, so the judge knows what each code means. (7) The prices-missing and self-judging notes from §8 are WARN logs at deploy, not refusals.
- **The judge's free text never travels.** Extra keys in the reply object are ignored, never forwarded; only declared codes are kept, mapped to their declared spelling.
- **Unusable `issues` at run time** (a config that bypassed deploy validation) is `judge_unavailable`, before any model call.

### Manager

- The guardrail editor offers `judge` on the output side (`guardrails.ts`: type list, phases, no-rewrite set, row summary, type text) with its own settings panel: provider, base URL, model and API key (shared with the classifier panel through a new `ModelCallFields`, so both write the provider's own model and credential keys), the required prompt template, an **issue-code editor** (rows of code + fix text, written back as the `issues` JSON string; codes upper-cased; a stored value that is not an object of strings is shown raw rather than rewritten; duplicates stay visible and are reported), timeout (default 5000) and prices (shared `CallLimitFields`), retrieved context, and warnings mirroring the deploy checks (missing provider, template or codes, malformed codes, unpriced judge).
- **`onExhausted`** is a select under Max retries on an output `reprompt` guardrail: `block` is stored as absent, `deliver-best` as set, and switching the action away from `reprompt` drops it (as it drops `maxRetries`). `deliver-best` on a `classifier` shows the same warning the deploy logs.
- 28 new `llmEditor.*` keys in all 11 locales. Tests: `task-guardrails-section.test.tsx` (+11) and `guardrails-judge.test.ts` (5).

### Platform Operator

- `system-prompt.ts`: the guardrail bullet lists `judge` and `onExhausted` (what deliver-best delivers, that it is re-screened, what the deploy refuses and warns about); a new `judge` bullet covers its parameters, the `issues` format, the closed-vocabulary reprompt, the could-not-decide reasons and failMode, cost and the timer, and the self-judging note; the docs pointer names the new topics. No allow-list change: the operator already edits LLM configs through `PATCH`. `operator-revision.json` 36 → 37.

### Tests and checks

`JudgeGuardrailTest` (47: parsing — fenced, prose-wrapped, undeclared codes, wrong shapes, oversized replies; closed-vocabulary hint and corrective message; template data; native schema enum, provider matrix and override; parameter pass-through; cost; unavailable/timeout/deadline; failMode both ways; validate bounds; warnings), `GuardrailServiceDeliverBestTest` (13: selection, ties, default block, re-screen with redaction, block and reprompt, deploy refusals and the classifier warning), `LlmTaskGuardrailsTest` (+5: acceptance reprompt with the author's fix text and judge cost in `audit:cost`; deliver-best in plain chat with PII redaction, SSE and audit records; tool loop; cascade; configure refusals), `GuardrailConfigDeserializationTest` (+1). Mutation-checked: free judge text in the hint, an undeclared code accepted, a missing `issues` field read as a pass, the re-screen removed, and a reprompt finding let through the re-screen — each fails the suite.

- **End-to-end:** `JudgeGuardrailIT` (9, Mongo) runs a real agent against a WireMock OpenAI endpoint that plays both the task model and the judge (told apart by the request's `model`): a pass (judge reply in a code fence), fail-then-repair (the second task-model request carries only the author's fix text for the found code, never the judge's free text; call counts per model asserted), exhaustion with `deliver-best` (fewest codes wins) and with the default `block`, and bad judge output (unparseable reply, undeclared code, HTTP 500) under `failMode` closed and open. Outcomes are read back through the conversation REST API and the audit ledger.

- **Review fix (docs):** `deliver-best` is re-screened only by the guardrails after the exhausted one, so on a `classifier` it delivers what that classifier flagged. `docs/security.md`, `docs/langchain.md` and the operator prompt no longer claim a classifier is never bypassed; a safety classifier should keep `onExhausted: "block"`. Operator revision stays 37, fingerprint updated.

- **Merge with `main` (#1130, history-replay protection).** Conflicts in `GuardrailService` (both sides added methods next to `validate`: this branch's `onExhaustedProblem`/`warnings`, `main`'s `anySkipsHistory` — all kept), `LlmTaskGuardrailsTest` (an import) and `operator-revision.json`. Two semantic fixes the auto-merge did not show: #1130 taught `runOutput` to act on a fail-open guardrail's partial finding (`GuardrailUndecidedException.partialFinding`), and the deliver-best re-screen now does the same, so a pattern list that timed out part-way still applies its rewrite to the delivered attempt (`GuardrailServiceDeliverBestTest.partialFindingStillApplies`); and `ClassifierGuardrail` lost its last `TimeUnit` use to #1130's `RegexDeadline` switch, so the import went. The operator prompt keeps both sides' additions; `operator-revision.json` 37 → 38.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
