## 🧪 test(secrets) + docs(backup): review of the groups, import and secrets fixes (2026-10-03)

**Repo:** EDDI (`review/p9`, on top of `fix/groups-import-secrets`).

### What changed and why

- **Grant contexts, real vault, real resealer.** New `ConnectionGrantVaultRoundTripTest` runs the three places that must agree on a grant's AAD context — sealing, opening and the DEK-rotation sweep — together over real AES-GCM, instead of each against a stand-in. It proves that legacy and bound grants both open after a rotation, that a token moved into another user's row, another connection's row or the other token field no longer opens, and that one unopenable grant leaves every other grant moved.
- **Import docs.** [import-export-an-agent.md](../import-export-an-agent.md#how-merge-tracking-works) now says what the scoped merge lookup means for an administrator: the import writes into the caller's own space unless `X-EDDI-Space` names a team they belong to, so re-importing to update a team's copy needs that header.
- **`RemoteApiResourceSource`.** The new size-cap helpers had been inserted between `configure`'s Javadoc and `configure`; moved above it.
