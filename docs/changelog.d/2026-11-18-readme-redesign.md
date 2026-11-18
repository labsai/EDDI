## 📝 docs(readme): the README is rewritten as a front page, not a manual (2026-11-18)

**Repo:** EDDI (`docs/readme-redesign`)

### What changed and why

**`README.md` went from 62 KB and 740 lines to about 14 KB and 200.** It had grown
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
2. **Why EDDI**: six bullets, and a plain statement that EDDI is a service you
   deploy rather than a library you import, with the libraries named for readers
   who want one. This replaces the comparison table, whose concurrency row
   ("virtual threads — true OS-level parallelism") was wrong and whose tone
   invited argument rather than evaluation.
3. **Quick start**: the two installer commands, the URL, what is served at which
   path, and how to get a first agent. Options and alternatives are links.
4. **What it looks like**: three screenshots instead of eleven.
5. **How it works**: an LLM agent's configuration (model, prompt, built-in
   tools), copied from [`langchain.md`](../langchain.md#agent-mode-enhanced-features)
   with the key as a vault reference, then who writes such documents and a short
   paragraph on the pipeline. Behaviour rules get one sentence: most agents are
   an LLM task with tools, and a first draft that led with a rule gave them far
   more room than their use warrants.
6. **What's included**: one table, eleven rows, each row linking to its guides.
7. **Documentation** by intent, **Development** (short), **Community and
   support**, **License**.

Headings no longer carry emoji, so their anchors are plain (`#quick-start`,
`#development`).

### Nothing was deleted, it moved

| Was in `README.md` | Now in |
| --- | --- |
| Installer options, `eddi update`, the CLI-not-found note, manual update, the upgrade pointer | [`getting-started.md`](../getting-started.md#installer-options) (Option 0) |
| The compose command matrix, the overlay list, the Ollama and NVIDIA notes | [`docker.md`](../docker.md#compose-files-and-overlays) |
| Prerequisites (Node, mise), dev-mode notes, the dev vault key, the Maven Command Reference, the system-property table, the JaCoCo path | [`CONTRIBUTING.md`](../../CONTRIBUTING.md#maven-command-reference) |
| The Kubernetes install block | already in [`kubernetes.md`](../kubernetes.md); the README links to it |
| The feature bullets, the docs index, the compliance table, the security bullet list | already in [`docs/README.md`](../README.md), the feature guides and [`security.md`](../security.md) |

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

### Not done here

- The repository description and topics on GitHub are settings, not files. The
  description says "19+ LLM providers" where the docs say 19.
- The social preview image (uploaded in repository settings) is still the old
  banner.
