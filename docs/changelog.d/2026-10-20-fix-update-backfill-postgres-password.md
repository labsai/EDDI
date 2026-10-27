## Fix: `eddi update` on a pre-#1034 Postgres install (2026-10-20)

**Repo:** EDDI, branch `fix/update-backfill-postgres-password`

An install made before `docker-compose.postgres-only.yml` required `POSTGRES_PASSWORD` has no such line in `.env`. `eddi update` refreshed the compose file, `docker compose pull` and `up` then failed to interpolate it, and the Windows wrapper still printed "EDDI updated."

- `install.sh` and `install.ps1` (the `eddi` wrapper): after refreshing the compose files, if one references `POSTGRES_PASSWORD` and `.env` lacks it, write the legacy `eddi` value and print a note. A fresh random value would lock EDDI out of the existing volume, because Postgres reads the variable only at initdb.
- `install.ps1` wrapper: `update` now stops with exit code 1 when `pull` or `up -d` fails instead of reporting success.
- New installs are unaffected: both installers already generate the password.
