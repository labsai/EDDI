## 🐛 fix(ci): publish the `-b` image for a merge that does not touch `ui/` (2026-10-21)

**Repo:** EDDI (`fix/ci-docker-push-backend-only`)

### What changed and why

Since the monorepo split of 2026-09-15 (`4f19e4767`, "run each half only when it changed"), no merge to `main` that changed only backend code published a `-b` image. Build & Test, the integration tests and the E2E runs on the built image all passed, and **Docker Build & Push** was skipped.

The cause is GitHub's implicit `success()`. A job condition without a status function gets one added, and for a job with `needs` it grades every job upstream, not only the listed ones. Docker waits on `ui-gate`, which is written to pass when the UI did not change (`always()`, skipped ui-* jobs accepted). On a backend-only push those ui-* jobs are skipped, so the implicit `success()` of docker was false and it was skipped — and with it Smoke Test and Preflight Verify (Pushed Image), which depend on it. Of the last 25 merges, every backend-only one was affected; tag builds were not, because detect-changes forces `ui=true` for a tag.

[`ci.yml`](../../.github/workflows/ci.yml):
- `docker` starts its condition with `!cancelled()` and grades its own `needs` explicitly: no `failure`, `cancelled` or `skipped` result. That keeps the gate the job's NOTE insists on — every listed job must have succeeded, so a failed or skipped test job still blocks the push, and no `always()` is used.
- `smoke-test` gets `!cancelled() && needs.docker.result == 'success'`, and `preflight-push` gains `!cancelled()` in front of its existing condition, for the same reason.

The tag-only jobs (release, Red Hat publish, post-release) are unchanged: on a tag the ui-* jobs always run.

### Verification

- The history check behind the diagnosis — Docker push outcome against the ui-* jobs for the last 25 `main` pushes — lines up one to one.
- `BuildQualityGatesTest#jobsBehindUiGateGradeTheirNeedsExplicitly` fails on any job behind `ui-gate` that runs on a non-tag push without a status function, and on a docker condition that loses its explicit `needs` checks or gains `always()`. Against the old `ci.yml` it fails, naming docker, smoke-test and preflight-push.
- The merge of this branch does **not** prove the fix: it changes `ci.yml`, which the `ui` path filter counts as a UI change, so its ui-* jobs run and it would publish anyway. The proof is the next backend-only push to `main` — it must publish a `-b` image and run Smoke Test.
