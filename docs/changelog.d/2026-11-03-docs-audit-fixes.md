## 📝 docs: fix the findings of the 2026-11-03 documentation audit (2026-11-03)

**Repo:** EDDI (`docs/audit-fixes-2026-11-03`)

### What changed and why

A 13-scope audit compared the docs, Javadoc and inline comments with the code. Every finding was re-checked against the code before it was fixed; the audit was wrong about a few, which were skipped.

**Code**
- Counterweight preset snippets are now `counterweight_cautious` / `counterweight_strict`. The hyphenated names could never be created (`PromptSnippetStore.NAME_PATTERN` is `[a-z0-9_]+`). The hyphenated names are still read as a fallback.
- `OrdinalNumbersDictionary` carried the id `punctuation` (copy-paste); it is now `ordinal_number`.
- `RestSemanticParser.parse` parameter renamed `parserId`.
- Everything else under `src/` is Javadoc or comment text only.

**Docs**
- Tenant quotas: only the default tenant is seeded, other tenants are unmetered until an admin sets a quota, and quotas are off by default.
- `rotate-kek` "TLS required" is not enforced by EDDI; the docs now say so. Default demo credentials are no longer listed in `security.md`.
- Compliance pages (`PRIVACY.md`, BAA template, audit ledger, data flow) no longer claim an immutable, always-signed ledger, a complete export, or unsalted pseudonyms (the DB-log pseudonym is `k1:`/`s1:`). EU AI Act citations corrected (Art. 50, 73). DLQ subject is `eddi.<prefix>.dlq.audit`.
- `AGENTS.md` §5 and the config-authoring pages: the templating step is only needed for output texts and quick replies, not for LLM prompts. `returnDetailed` withholds audit, trace and error keys.
- The vars/vault resolution order in `global-variables.md`, the HITL crash-recovery description, the MCP tool count (96) and the discussion-style count were corrected.
- `SECURITY.md` (root and Manager): only the latest release is supported; older versions get no backported fixes.
- `ui/manager/SECURITY.md`: the react-router advisory acceptance is restated for the data router the app uses.

**Deferred** (editorial or structural): splitting the very large pages (`langchain.md`, `hitl.md`, `configuration-reference.md`, `metrics.md`, `rag.md`, `security.md`, `secrets-vault.md`), the duplicated Manager community files, a 6.6 to 6.7 upgrade page, I-code and Phase labels in comments. Open code gaps: the 10,000-row audit export cap is not signalled in the bundle, and some export categories and block failures are not reported in `failedCategories`/`omittedCategories`.
