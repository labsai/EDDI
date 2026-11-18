## 📝 docs(agents): AGENTS.md keeps rules and pointers, not facts other files own (2026-11-18)

**Repo:** EDDI · **Branch:** `docs/agents-md-durable`

### What changed and why

The root [`AGENTS.md`](../../AGENTS.md) was edited 57 times between 2026-09-01 and this
change. Almost none of those edits changed a rule: they corrected a count, a CI job name, a
version, a migration date or a roadmap row that some other file owns and had moved on from.
The file is loaded into every session, so each such sentence also cost context every time.
This pass removes that class of content (66 KB → 59 KB) and writes down what may go in.

- **A maintenance rule at the top** ("Editing this file?"): no restating what `pom.xml`,
  `ci.yml` or `docs/` own, no history, features get a row in §3 and a page under `docs/`,
  and section and rule numbers are never renumbered because tests, Javadoc, `ci.yml`
  comments and the skills cite them.
- **Removed, with a pointer to the owner where one was needed:** the MCP tool, LLM provider,
  discussion-style and test-method counts; Java, Node and Quarkus versions; the lists of CI
  job names; the repository-migration dates and the one-time `./mvnw clean` note; the
  GitBook cutover status; the count of Checkstyle violations in `src/test/java`; the
  enumeration of `ToolSourceProvider` implementations; the "pending consolidation" status of
  the outbound HTTP clients.
- **§3 no longer carries an "In Progress / Upcoming" table.** It said of itself that it
  lagged. The section now names the two sources that do not: the changelog for what landed
  and [`planning/`](../../planning/) for what has not. §6 follows (it pointed at the table).
- **§2 rule 8 (changelog) is the rule plus a pointer** to [`README.md`](../changelog.d/README.md);
  the collator's mechanics, the CI job's matching rules and the size thresholds are in that
  README, in `ci.yml` and in the skill, and were a third copy here.
- **§2 rule 4's merge rules are three short rules**; the procedure that satisfies them (the
  `gh` commands, the up-to-date check, the merge queue) moved to a new §7 "Merging" in
  [`.claude/skills/ship-pr/SKILL.md`](../../.claude/skills/ship-pr/SKILL.md). The rules
  themselves are unchanged, and the incident that motivated them stays in the changelog.
- **"Trivy CVE Remediation" is a heading again.** `EddiImageDockerfileTest` and a `ci.yml`
  comment cite it by that name, and it had become a bold lead-in.

Drift fixed in the other agent-instruction files:

- [`.cursor/rules/project-context.mdc`](../../.cursor/rules/project-context.mdc) named the
  Manager and the Chat UI but not Workforce; it now names the rule (the app's own
  `AGENTS.md`) rather than a list.
- [`ui/manager/AGENTS.md`](../../ui/manager/AGENTS.md) and
  [`ui/chat/AGENTS.md`](../../ui/chat/AGENTS.md): Workforce added to the ecosystem and
  workspace descriptions ("both UIs" → "more than one UI"). Deliberately minimal — two open
  PRs edit the Manager's file.
- `ship-pr`: the `Changelog Discipline` description matched only dated headings (the job
  counts any `## ` heading, setext headings and register rows, net of removals);
  `JlamaRuntimeFlagsTest` was missing from the guards to run after touching `AGENTS.md`; the
  `backend` path filter list lacked `docs-site/**/*.md` and `.gitignore`.

### Design decisions

- **Numbering kept, content cut in place.** §1–§6 and every numbered rule keep their
  numbers and titles; the three test-pinned sentences (`8. **Update the changelog` naming
  `docs/changelog.d`, `` `src/main/java` only ``, the `quarkus:dev` line with its
  `-Djvm.args` flag) are unchanged.
- **§4's technical content was not rewritten.** It was reviewed line by line against the
  code on 2026-11-14 and is the durable part of the file.

### What's next

- `ui/manager/AGENTS.md` (43 KB) has the same disease in a different form: phase and ticket
  narrative (T1.9, T2.7, "phase 7 added…") and a long Platform Operator section that
  restates `tool-scopes.ts` and the operator docs. It deserves the same pass, after the PRs
  currently editing it have merged.
