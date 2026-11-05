## 📝 docs(release): close the 6.7.0 upgrade-guide and release-notes gaps (2026-11-05)

**Repo:** EDDI (`docs/release-6.7.0-docs-gaps`)

### What changed and why

"Changes in 6.7" in [`upgrading-from-6.5.md`](../upgrading-from-6.5.md) and the 6.7.0 row of
[`release-notes.md`](../release-notes.md) were written before the team learning loop, team lessons, the agent
operations tools, the tenant cost reservation, per-run `toolQuotas`, the email channel, conversation ratings and alert
rules were all merged. The upgrade guide now lists each in "New opt-in features" with its switch and page, and
"Rolling upgrades" says which stored fields (`TaskItem.bet`, `lessonConfig`) an older pod drops on save, which new
collections and tables older pods ignore, and that the alerts maintenance schedule exists by default. The release-notes
row gets the date 2026-11-05 and the missing headline items. [`email-integration.md`](../email-integration.md) said
"Since v6.6.0"; the channel merged after the 6.6.0 tag, so it now says v6.7.0.

### Design decisions

- The date is the planned release date: the `6.7.0` tag did not exist yet when this was written. Correct it if the
  tag lands on another day.
- Release pointers (Helm `appVersion`, manifests, docs commands) are left to the post-release PR.
