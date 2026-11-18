## 📝 docs(readme): the README is rewritten as a front page, not a manual (2026-11-18)

**Repo:** EDDI (`docs/readme-redesign`)

### What changed and why

**`README.md` is restructured: a 200-line front page, then the full feature list.** It had grown
by accretion: every feature added a bullet, every deployment fix a paragraph, and
the result was a page whose first screen was nine badges and a table of contents,
whose middle was 200 lines of feature bullets and a 43-row index that duplicated
[`docs/README.md`](../README.md), and which printed operator runbooks (the compose
overlay matrix, the Kubernetes Secret procedure, the Maven command table) inline.
It never showed one line of agent configuration, in a product whose pitch is that
agents are configuration.

The new page answers, in order, what a visitor asks: what is this and is it for
me, how do I run it, what does it look like, how does it work, what is in it,
where are the docs, how do I build it. Sections:

1. Banner, name, the badges (the ten the page had, plus the licence), one
   paragraph, link row.
2. **Why EDDI**: run, limit, approve, record, then two design choices, and a plain statement that EDDI is a service you
   deploy rather than a library you import, pointing readers who want a library
   at LangChain4j (which EDDI builds on) rather than at frameworks in other
   languages. This replaces the comparison table, whose concurrency row
   ("virtual threads — true OS-level parallelism") was wrong and whose tone
   invited argument rather than evaluation.
3. **Quick start**: the two installer commands, the URL, what is served at which
   path, and how to get a first agent. Options and alternatives are links.
4. **What it looks like**: three screenshots instead of eleven, one each for
   run, approve and record.
5. **How it works**: an LLM agent's configuration (model, prompt, built-in
   tools), copied from [`langchain.md`](../langchain.md#agent-mode-enhanced-features)
   with the key as a vault reference and Claude Sonnet 5.5 as the model, then who writes such documents and a short
   paragraph on the pipeline. Behaviour rules get one sentence: most agents are
   an LLM task with tools, and a first draft that led with a rule gave them far
   more room than their use warrants.
6. **What's included**: one table, eleven rows, each row linking to its guides.
7. **Documentation** by intent, **Development** (short), **Community and
   support**.
8. **Full feature list**: every feature the old page listed, kept at the end for
   readers who want it on one page and for in-page search, but rewritten to be
   lighter: one short line per feature, plain headings, a link row to the guides
   under each heading, and no em dashes. Two claims are corrected on the way:
   virtual threads are not "OS-level" concurrency, and SSRF validation is the
   built-in tools', not every outbound call's.
9. **License**.

Headings no longer carry emoji, so their anchors are plain (`#quick-start`,
`#development`).

### Positioning

The page now calls EDDI "the open-source AI agent management system" and
organises **Why EDDI** around four verbs: run, limit, approve, record.
"Multi-agent orchestration middleware" described one capability; what the
product does around an agent (grant its tools, cap its cost, gate its actions,
keep the ledger) is management, and that is what a reader evaluating it for a
regulated setting is looking for. `AGENTS.md`, `docs/README.md` and the
repository description still use the older phrase; aligning them is a separate
decision.

### Nothing was deleted, it moved

| Was in `README.md` | Now in |
| --- | --- |
| Installer options, `eddi update`, the CLI-not-found note, manual update, the upgrade pointer | [`getting-started.md`](../getting-started.md#installer-options) (Option 0) |
| The compose command matrix, the overlay list, the Ollama and NVIDIA notes | [`docker.md`](../docker.md#compose-files-and-overlays) |
| Prerequisites (Node, mise), dev-mode notes, the dev vault key, the Maven Command Reference, the system-property table, the JaCoCo path | [`CONTRIBUTING.md`](../../CONTRIBUTING.md#maven-command-reference) |
| The Kubernetes install block | already in [`kubernetes.md`](../kubernetes.md); the README links to it |
| The docs index, the compliance table, the security bullet list | already in [`docs/README.md`](../README.md), the feature guides and [`security.md`](../security.md) |

`AGENTS.md` pointed at `README.md#️-development` and called the README's Maven
table canonical; both now point at `CONTRIBUTING.md`.

### Build inputs

The README is graded by tests, which shaped three choices. It still prints one
`quarkus:dev` command carrying `-Djvm.args=--add-modules=jdk.incubator.vector`
(`JlamaRuntimeFlagsTest` requires every dev-mode document to show one). It prints
no `kubectl apply -k` and no `labsai/eddi:latest` (`DeploymentManifestsTest`), and
no multi-file compose command (`ComposeStackTest`); the commands that moved to
`docker.md` are the same combinations the matrix already renders.

### Images

- **Banner:** `screenshots/eddi-banner.jpg`, rendered from
  [`branding/readme-banner/banner.html`](../../branding/readme-banner/banner.html).
  The old one was a 1200×630 social card (tall for a README) whose tagline, "The
  Enterprise AI Orchestrator", matched neither the README nor the repository
  description. The new one is 1280×380, carries the README's own line, and shows
  an agent's configuration instead of generic artwork.
- **Screenshots:** retaken from the current Manager on its mock API; the old set
  showed the pages as they looked at 6.0. The mock API always reports version
  `6.0.0-demo`, so the capture hides the sidebar's version label rather than
  print a release number that never changes.
- **[`ui/manager/scripts/capture-readme-images.mjs`](../../ui/manager/scripts/capture-readme-images.mjs)**
  regenerates all of them, so the next refresh is one command rather than a
  manual session.

### "Works with the AI you already use"

A new section after **Why EDDI** answers a question the page left open: is EDDI
a replacement for ChatGPT or Claude? It is not. The Manager lets you do
everything by hand, but the intended experience is to connect the assistant you
already use over MCP or REST and have it build and operate agents, or consult
EDDI agents that hold access (a database, an internal API) the assistant does
not. The section also says EDDI works alone, through Workforce, the chat UI or
Slack.

`AGENTS.md`, `docs/README.md` and `docs/mcp-server.md` said 108 MCP tools; all
now say 120.

### Three independent reviews

The page was reviewed by three models with the same brief. Where they agreed,
it changed:

- **The sample ran nothing.** It listened for a `help` action that no rule
  emitted. It now uses `"actions": ["*"]`, and the text calls it the LLM step of
  an agent rather than the agent.
- **Governance claims carry their conditions.** The ledger is "HMAC-signed once
  a signing key is configured"; agent signing is "optional"; the Red Hat line
  says what CI does (checks every image against the certification requirements)
  rather than asserting a status.
- **The MCP tool count is 120**, which is what `McpToolContractTest` pins. The
  README said 108, as `AGENTS.md`, `docs/README.md` and `docs/mcp-server.md` did.
- **The quick start reaches a reply**: a health check, where the model key goes,
  the wizard or the Operator, then chat. It says the default install has no
  login and is loopback-only, and that a hand-run `docker compose up` needs
  `EDDI_VAULT_MASTER_KEY`.
- **The screenshots show the pitch**: a group discussion (run), the pending
  approvals page (approve) and an agent's audit trail (record) replace the
  dashboard and a rule-driven conversation.

Not taken: moving the full feature list out to a docs page, and restoring the
comparison against Python frameworks. Both were decided the other way on
purpose.

### Review findings on the moved text

Moving the installer, compose and build material put old text in front of a
reviewer for the first time, and some of it was wrong:

- **`OLLAMA_PULL_MODEL=` did not skip the model pull.**
  [`docker-compose.ollama.yml`](../../docker-compose.ollama.yml) read it with
  `${OLLAMA_PULL_MODEL:-llama3.2:3b}`, and `:-` substitutes the default for an
  empty value as well as an unset one. It is now `-`.
- **The PostgreSQL and cluster commands generated new secrets on every run.**
  The database volume keeps its first password and the vault its first master
  key, so the second run failed or could not decrypt. `docker.md` now writes
  them into `.env` once.
- **`eddi update` does not always pull the latest image.** A pinned install
  stays pinned; the page says so and shows `--eddi-version`. The manual
  `docker compose pull` is no longer called equivalent, because it does not
  refresh the compose files.
- **The installer options could not be used after `curl | bash`**, which keeps
  no script. A download line comes first.
- **`echo … > .env` replaced an existing `.env`**, vault key included. It
  appends now.
- **`-Dquarkus.profile` does not default to `dev`** outside dev mode.
- **Single-quoted `-Djvm.args` does not work in `cmd.exe`.**
- **A wizard-created agent is not in Workforce**: the wizard deploys to `test`,
  and Workforce lists production agents marked as listed.

### Not done here

- The repository description and topics on GitHub are settings, not files. The
  description says "19+ LLM providers" where the docs say 19.
- The social preview image (uploaded in repository settings) is still the old
  banner.
