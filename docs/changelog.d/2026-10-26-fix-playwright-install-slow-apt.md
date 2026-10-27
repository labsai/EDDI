## 🐛 fix(ci): the Playwright install no longer waits on the Ubuntu apt mirror (2026-10-26)

**Repo:** EDDI (`fix/playwright-install-slow-apt`)

### What changed and why

`main` went red on the `fast-uri` bump (`daecb3513`) with no test failing. `UI Manager E2E (MSW)` and `Backend E2E (postgres)` both hit the 10-minute limit of `Install Playwright browsers`, and the re-run failed the same way. `npx playwright install --with-deps chromium` always runs apt-get. On `ubuntu-latest` that meant about 26 MB of fonts (CJK, unifont, xfonts) plus a few library upgrades from `azure.archive.ubuntu.com`, which was serving 25–50 KB/s that afternoon. None of it is needed: the suites take screenshots only on failure and compare none, and the runner image already carries Chromium's shared libraries.

The three steps that installed Playwright (`UI Manager E2E (MSW)`, `Backend E2E`, `Auth E2E (Keycloak)`) now run [`ui/manager/scripts/install-playwright-chromium.mjs`](../../ui/manager/scripts/install-playwright-chromium.mjs). It:

- downloads the browser from Playwright's CDN (no apt);
- launches it once, and if that works, skips apt altogether;
- otherwise prints a `::warning::`, runs `npx playwright install-deps chromium`, and fails the step if Chromium still does not launch.

So a runner image that drops a library still works, it just pays the apt cost again, and the warning says so.

### Verified

Both paths ran in Docker against `@playwright/test` 1.64.1:
- `node:24-bookworm` (no Chromium libraries): the probe reported a missing shared library, apt installed the dependencies, the second launch succeeded, exit 0.
- `mcr.microsoft.com/playwright:v1.64.1-noble`: launched first time, apt skipped, exit 0.

The `ubuntu-latest` path itself is verified only by this PR's own CI run.
