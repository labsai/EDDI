## 📝 docs(upgrade): an upgrade guide from 6.4 to 6.5 (2026-09-30)

**Repo:** EDDI (`docs/650-upgrade-guide`)

### Why

An operator upgrading an existing 6.4.x deployment had no single place that says what breaks and
what to change. Several 6.5 changes take a working 6.4 deployment down without one: a Keycloak
realm imported from the 6.1–6.4 realm file answers `401` on every request, an IdP that delivers
roles outside `realm_access/roles` answers `403`, `helm upgrade` with the in-chart MongoDB refuses
to render, and a weak vault master key stops a production boot.

### What changed

- New [`upgrading-from-6.4.md`](../upgrading-from-6.4.md), in the shape of
  [`upgrading-from-5x.md`](../upgrading-from-5x.md): before you start (backup, no mixed versions,
  no rollback without the backup), the changes that break a working deployment, then
  authentication, refusals, outbound HTTP, Helm/Kubernetes/Compose/image, client-visible REST, MCP
  and SSE changes, automatic database changes, the extra checks for a database that 6.4 migrated
  from 5.x, and how to check the result. Each item says what changed, who is affected and what to
  do, and links the detailed page instead of repeating it.
- Linked from the README documentation table and `docs/SUMMARY.md`, next to the 5.x guide.

### Decisions

- Every item was checked against the code on this branch (property, `@ConfigProperty` default,
  model field, refusing code path, role annotation, chart template), not taken from changelog
  entries. An earlier draft said HTTP calls resolve `${vault:…}` in URL, body and query "for the
  first time"; 6.4.0 already did, so the guide describes what is actually new: the rule that a
  credential reference must come from the configured template, and the fail-closed handling of
  an unresolvable one.
- The guide warns that two boot-time steps delete duplicate documents (`usermemories` identities
  and deployment rows), which is why the backup comes first.
- No concrete image tag is written: the release pointers still name 6.4.0 until the post-release
  update, so the guide tells Helm users to set `eddi.image.tag` explicitly.
