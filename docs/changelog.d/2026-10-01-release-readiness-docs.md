## 📝 docs: upgrade guides cover the 6.5 fixes; stale Open WebUI and RAG claims; two names the reference guard wrongly refused (2026-10-01)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### Upgrade guides

[`upgrading-from-5x.md`](../upgrading-from-5x.md):
- a configuration row and a section on the idle sweep, which first runs five minutes after boot. From
  6.5 a limit below 1 disables idle-ending; before 6.5, `-1` ended every conversation;
- the conversation-descriptor rename;
- the new reporting of unconvertible templates (one ERROR, then one WARN per boot);
- the by-agent listing as a check;
- a precise description of which legacy properties are held back.

[`upgrading-from-6.4.md`](../upgrading-from-6.4.md):
- the two one-time catch-ups a 6.4-migrated database gets (step shape, descriptor fields and their
  backfill), with count queries to confirm them;
- the idle-limit `-1` change;
- the NEGOTIATION fix, which changes the outcome of stored groups: a real verdict instead of a
  summary recorded as one.

### Stale claims corrected

- [`open-webui-integration.md`](../open-webui-integration.md) and `docker-compose.openwebui.yml` said
  the `/v1` adapter "is not in any published image yet". It has shipped since 6.4.0; the demo builds
  from the working tree so it runs the checkout's adapter.
- [`rag.md`](../rag.md) called `web` "the only type implemented", above its own `upload` section.
- A Manager comment in `ui/manager/src/lib/hitl-config.ts` said the backend runs the generic synthesis
  prompt for a NEGOTIATION group missing its arbitration prompt. Since 6.5 the backend restores the
  prompt itself, with the same matching rule.

### `ConfigurationReferenceCoverageTest`

The guard refused two real settings in any document:
- `EDDI_BIND`, the Compose variable that sets the address EDDI's ports are published on. It's now in
  the non-property list beside `EDDI_PORT`.
- `EDDI_CHAT_FRAME_ANCESTORS`. Its property is read through a `${...}` expression rather than by Java,
  so the guard's scan of the code couldn't see it. The environment names of the properties in
  `DECLARED_BUT_UNREAD` now count as valid.

The README now names `EDDI_BIND` directly, where it had to describe the variable's location instead.
The 6.4 guide names `EDDI_CHAT_FRAME_ANCESTORS`. Both changes were mutation-checked: removing either
one fails `documentedEnvironmentVariablesMapToRealProperties` on those docs.
