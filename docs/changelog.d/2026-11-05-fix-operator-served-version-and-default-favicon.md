## Operator screen follows the version that serves it; the default favicon is the current icon again (2026-11-05)

**Repo:** EDDI, branch `fix/operator-setup-and-default-favicon`

### Operator screen pinned to the activated version

**What broke:** the Manager records the Operator's agent version once, at activation (`config.version` in `platform.operator`), and never refreshes it. An admin who edits the Operator in place and deploys the result runs v2 while the config still says v1. The chat was unaffected (a conversation starts by agent id and environment, so the backend serves the deployed version), but everything around it described v1: the status card polled v1's deployment status and showed "Version 1", the health banner offered to redeploy v1 once v1 was undeployed, and the gate dry-run checked v1's approval gate rather than the gate of the version actually answering.

**What changed** (`ui/manager`):
- `servedOperatorVersion` in `src/lib/api/operator.ts`: the highest version deployed in the Operator's environment (`GET /administration/{env}/deploymentstatus`), else the newest stored version (a paused Operator), else the recorded one.
- `readOperatorStatus` reads the status at that version and returns it (`OperatorStatus.version`); the status card's Version row shows it; `RegisteredOperatorHealth` redeploys it; `gateDryRun` sends it.
- Tests in `src/lib/api/__tests__/operator.test.ts`, mutation-checked: reverting `readOperatorStatus` and `gateDryRun` to `config.version` fails two of them.

Teardown already used the live version (`liveOperatorVersion`, after a reconfigure left two Operators deployed); this extends the same rule to the readers.

### Default favicon

**What broke:** the favicon slot's bundled default (`BrandAssetSlot.FAVICON`) was `META-INF/resources/img/favicon.ico`, which in the jar is the Chat UI's `ui/chat/public/img/favicon.ico`, a copy of the old EDDI logo unchanged since February 2024. Every other copy (Manager, Swagger UI branding, Keycloak theme, docs site) is the current icon. An unbranded install therefore served the old logo at `/branding/favicon.ico`, the browser-tab icon of every UI, and Admin → Branding showed it cropped.

**What changed:**
- `ui/chat/public/img/favicon.ico` replaced with the current icon (`ui/manager/public/eddi-icon.ico`).
- The slot's default is now `META-INF/branding/favicon.ico`: in source control, on the classpath in every build (including `-DskipUi`), and already the current icon.
- New `DefaultFaviconConsistencyTest` fails when any bundled copy differs from `ui/manager/public/eddi-icon.ico`, or when the slot's default is not on the classpath or not that icon.
