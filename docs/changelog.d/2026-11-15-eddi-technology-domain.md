## Live links move from eddi.labs.ai to eddi.technology (2026-11-15)

**Repo:** EDDI · **Branch:** `fix/redact-encoded-secret-echoes` (#1372, carried here by the owner's decision)

**What changed:** the project site moved to `eddi.technology`, and `eddi.labs.ai` only redirects there now. The live links now point at the new domain directly:
- Docs: `GOVERNANCE.md` and `docs/rag.md` (the `README` itself has no `eddi.labs.ai` link).
- The installers' closing message (`install.sh`, `install.ps1`) and the startup `banner.txt`.
- The OpenAPI contact URL in `application.properties`, with `BrandedOpenApiRouteTest` updated to match.
- The image label in `src/main/docker/Dockerfile`.
- The crawler's default User-Agent (`EDDI-Crawler/1.0 (+https://eddi.technology)`), documented in `docs/rag.md`.
- The "Website" link in the Docker-pull Slack notice.
- The Helm chart's `home`, and its `icon`, which was broken: `eddi.labs.ai/img/eddi-logo.png` redirected to a 404. It now points at `https://eddi.technology/eddi-icon.png`. The chart version moves from 2.14.1 to 2.14.2, with `EXPECTED_CHART_VERSION` in `DeploymentManifestsTest`.

**Left alone on purpose:**
- `AGENTS.md` and `planning/docs-site-migration-plan.md`, which describe the redirect itself.
- The monthly changelog archives.
- Tests that use the old URL only as arbitrary test data.
- The Umami analytics `hostname` in `docker-pull-notify.yml`: changing it would split the project-metrics history across two hostnames.
- `docs.labs.ai`, the `labsai` GitHub and Docker names, and `labs.ai` e-mail addresses. They are different things, and the docs-site cutover is planned separately.
