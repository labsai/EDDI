## 📝 docs(governance): release tags carry no `v` prefix (2026-09-28)

**Repo:** EDDI (`docs/governance-bare-release-tags`)

### What changed and why

[`GOVERNANCE.md`](../../GOVERNANCE.md) said releases are tagged `v6.0.0`, `v6.0.1` and
`v6.0.0-RC1`. That contradicts AGENTS.md §1, [`release-versioning.md`](../release-versioning.md) and
`ci.yml`, which triggers only on tags matching `[0-9]*`. A `v`-prefixed tag starts no workflow at
all: no image, no cosign signature, no SLSA attestation and no GitHub release. The push succeeds
anyway, so nothing reports the problem. GOVERNANCE.md is where a new maintainer looks for "how do we
release", so it was the one place still pointing them at the tag that does nothing.

The page now uses bare tags (`6.0.0`, `6.0.0-RC1`), says why the prefix matters, and links to
`release-versioning.md` for the process.

### Checked, not changed

A repo-wide search for `v6.`/`v5.` versions and `git tag` instructions, excluding the changelog,
found no other place that tells someone to push a `v`-prefixed tag. The remaining hits are:

- "Available since v6.0.0" status lines, which name a release, not a tag to create.
- `# v6.0.3` comments on pinned GitHub Actions.
- The existing warnings against the prefix, in `release-versioning.md` and `release-signing.md`.
