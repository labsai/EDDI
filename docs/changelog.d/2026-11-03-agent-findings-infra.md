## 🔒 fix(infra): agent-findings triage of the installer, GCP provisioner, CI and load-test scripts (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-infra`)

### What changed and why

A batch of 35 machine-generated security findings against the infrastructure files was
checked against the current code. Most were already closed by the earlier infra review
pass (#1185); the rest are fixed here or recorded as accepted risk or open decisions.

- **`install.sh`** sends Keycloak and Grafana credentials to `127.0.0.1` instead of
  `localhost`. The Docker ownership check matches the published IPv4 port, but `localhost`
  can resolve to `::1` first, where another local user's listener could hold the same port.
- **`gcp/provision-vm.sh`**: the generated startup script reads Keycloak's real port from
  `.env` (the installer moves off an occupied 8180), sends the admin password only to a port
  Docker reports as a running `keycloak` container's, uses the same port in nginx, and passes
  the admin bearer token through a curl config on stdin rather than `-H` (argv).
- **`src/main/docker/seed-demo-agent.sh`**: the `/v1/models` bearer key goes through
  `curl -K -` instead of `-H`.
- **`.github/workflows/ci.yml`**: the PR title shown in the Slack notification is escaped
  (`&`, `<`, `>`), so it cannot form a link in the mrkdwn field.
- **`scripts/loadtest`**: result and report files are written through an exclusive temp file
  and `os.replace`, so a symlink planted in the world-writable `results/` directory is
  replaced rather than followed. The stream driver reads bodies and SSE frames in linear time
  and caps the error body it keeps.
- **`k8s/overlays/monitoring`**: a `NetworkPolicy` admits only the Grafana pod to Prometheus.
- **`application.properties`**: a comment says the metrics permission path is literal and must
  be repeated if the endpoint is relocated.

- **`install.sh` / `install.ps1`** (PR #1099 review): a fresh install (no `.env` yet) now
  generates its own `MONGO_APP_PASSWORD`, so EDDI no longer connects to MongoDB with the
  root password under another name. An existing `.env` is never given one (its app user
  already exists with the old password); a stored value is carried forward as before.

- **PR #1239 review follow-ups:** the GCP startup script now sets `EDDI_DIR` before it reads
  Keycloak's port (it read `/.env` and always fell back to 8180); the credential-bearing
  `curl` calls in `install.sh` pass `--noproxy 127.0.0.1`; `install.ps1` takes an exported
  `MONGO_APP_PASSWORD` as the value it persists; the stream driver reads a chunked body in
  64 KiB pieces; `write_text_atomic` sets the mode with `fchmod` on the open descriptor and
  keeps an existing file's mode or the umask default; the NetworkPolicy and metrics-path
  notes are corrected.

- **PR #1239 outside-diff review:** the Grafana health probe and the GCP script's Keycloak
  grant and admin calls also pass `--noproxy 127.0.0.1`; the GCP script re-checks that a
  running Keycloak container owns the port on every attempt instead of once; `install.ps1`
  removes only one matching outer quote pair when reading `.env`.

- **Stream driver, SSE across piece boundaries:** `sse_events` holds a trailing `` until the next piece
  and decodes incrementally, so a CRLF or a multi-byte character split by the 64 KiB reads no longer
  delays or merges events.

- **PR #1239 third review:** `sse_events` resumes its `"\n\n"` search one character before each
  new piece instead of rescanning an incomplete frame from its start, and fails the turn as a
  protocol error once a frame passes 1 MiB (`MAX_SSE_FRAME`, the reader's existing line limit);
  `report --out` / `cluster-report --out` naming a special file such as `/dev/stdout` is written
  in place rather than replaced; the GCP script takes the Keycloak container id from
  `docker compose ps -q keycloak` with the stack's own compose arguments, so a keycloak of
  another Compose project on the same port is never sent the admin password.

- **PR #1239 fourth review:** a complete SSE frame is held to the same 1 MiB limit before it is
  yielded, so the verdict no longer depends on where the 64 KiB boundaries fall. `--out
  /dev/stdout` (or `-`) now writes to the process's stdout descriptor. It works even when stdout
  is redirected to a file, where a stat reports a regular file and the temp file would have
  landed in `/dev`. No other `--out` path is opened in place, so a FIFO planted in `results/`
  is replaced rather than written to. This drops the stat-based special-file branch.

### Verdicts (35 findings)

FIXED 13, ALREADY_FIXED 14, ACCEPTED_RISK 4, NEEDS_DECISION 4 (the Copilot auto-approve
workflow's trust boundary, the shared GCP firewall rules, the updater's compose checksums,
the Manager client's `localhost:*` redirect URIs).

**Files:** [`install.sh`](../../install.sh), [`gcp/provision-vm.sh`](../../gcp/provision-vm.sh),
[`seed-demo-agent.sh`](../../src/main/docker/seed-demo-agent.sh),
[`ci.yml`](../../.github/workflows/ci.yml), [`loadtest.py`](../../scripts/loadtest/loadtest.py),
[`stream_driver.py`](../../scripts/loadtest/drivers/stream_driver.py),
[`monitoring-stack.yaml`](../../k8s/overlays/monitoring/monitoring-stack.yaml),
[`InfraReviewHardeningTest.java`](../../src/test/java/ai/labs/eddi/deploy/InfraReviewHardeningTest.java)
