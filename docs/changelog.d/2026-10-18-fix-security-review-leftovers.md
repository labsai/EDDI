## fix(security): the four leftovers of the 2026-10-17 security review (2026-10-18)

**Repo:** EDDI · **Branch:** `fix/security-review-leftovers`

The 2026-10-17 review was remediated in PRs #1079–#1094. Four items were left on purpose and
are closed here, one commit each.

### 1. Keycloak refuses the admin/admin fallback off loopback

[`docker-compose.auth.yml`](../../docker-compose.auth.yml) falls back to `admin`/`admin` for the
Keycloak bootstrap admin so the loopback quick start needs no `.env`. That was safe only while
`KEYCLOAK_BIND` stayed on loopback, and nothing enforced it. A new one-shot `keycloak-guard`
service, the same pattern as `docker-compose.yml`'s `bind-guard`, now exits 1 (and Keycloak,
which waits for it with `service_completed_successfully`, never starts) when `KEYCLOAK_BIND` is
not loopback while `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin`. It reads both values from its
own environment with the defaults the keycloak service uses. It runs on the Keycloak image the overlay pulls anyway, so it adds no image to
either base stack (`docker-compose.postgres-only.yml`'s own guard avoids the mongo image for the
same reason). The installers and
`gcp/provision-vm.sh` already generate a password, so their paths pass unchanged.

- `ComposeStackTest` pins the wiring (dependency condition, same password expression, same bind
  variable as the published port) and runs the guard script itself through `sh` for five
  bind/password combinations; that test is skipped only where no POSIX `sh` is on `PATH`.
  `adminPasswordsAreOverridable` now checks that each admin-password variable appears at least
  once, since the guard repeats `KC_BOOTSTRAP_ADMIN_PASSWORD`.
- Verified by hand with `docker compose run keycloak-guard` against loopback, `0.0.0.0` with no
  password, with `admin`, with an empty one and with a real one.
