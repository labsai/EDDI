## 🔒 chore(docker): base images with the Java4Shell-fixed JDK; Keycloak 26.8.2 pinned by digest (2026-11-21)

**Repo:** EDDI (`chore/base-images-fixed-jdk`)

### What changed and why

All three base images EDDI ships now have a build whose JDK fixes Java4Shell (CVE-2026-114938). Until now the
repository pinned builds with a vulnerable JDK and relied on the interim `-XX:-UseBASE64Intrinsics` flag:

| Image | Was | Now | JDK |
| --- | --- | --- | --- |
| `ubi10/openjdk-25-runtime:1.24` (production, both `FROM` lines) | `dab1be80…`, built 2026-11-11 | `6110c805…`, built 2026-11-19 (`1.24-21`) | OpenJDK 25.0.5.0.8 → 25.0.5.1 |
| `eclipse-temurin:25-jre` (demo runtime) | `a640968a…`, built 2026-11-11 | `4ed132f6…`, built 2026-11-20 | `jdk-25.0.5+8` → `jdk-25.0.5.1+2` |
| `quay.io/keycloak/keycloak` (compose `auth` overlay, Helm, kustomize `auth` overlay, the Manager's Keycloak compose files) | `26.8.0` (tag only, 2026-10-10) | `26.8.2@sha256:7e66a96e…`, rebuilt 2026-11-19 | OpenJDK 21.0.12.1 → 21.0.13.1 |

- **Keycloak is pinned by digest as well as by tag.** Keycloak rebuilt the `26.8.2` tag on 2026-11-19 with the
  fixed JDK, so a node that pulled `26.8.2` before then still holds the vulnerable build under the same tag. The digest
  pins the rebuilt one. The move from 26.8.0 to 26.8.2 also brings Keycloak's own fixes from 26.8.1 and 26.8.2. CI's
  `Auth E2E (Keycloak)` job runs the Manager's Keycloak compose file on it. No CI job starts `docker-compose.auth.yml`,
  so its stack was run by hand on 26.8.2 (2026-11-21): `keycloak-guard` passed, the `eddi` realm imported,
  Keycloak started healthy on OpenJDK 21.0.13.1 with the flag active, and `keycloak-admin-rotate` rotated a default
  `admin/admin` account through `kcadm` `reset-password`, verified the configured login and wrote its marker, and
  passed again on a rerun.
- **Helm chart 2.14.3 → 2.14.4**, with `EXPECTED_CHART_VERSION`. `keycloak.image` gains a `digest` field, rendered as
  `tag@digest` the way `nats.image` already is. The digest selects the image, so an operator who overrides
  `keycloak.image.tag` must also set `keycloak.image.digest` to the matching digest, or clear it with
  `--set keycloak.image.digest=""`; a tag-only override still runs the pinned 26.8.2 build. `ThirdPartyImagePinsTest`
  now requires the same Keycloak
  `MAJOR.MINOR.PATCH@sha256` reference on every delivery path (compose, `ui/*` compose, Kustomize, Helm).
- **Confirmed inside the images:** `java -version` in each pinned image (amd64) prints `25.0.5.1`
  (Red_Hat-25.0.5.1.2-1), `25.0.5.1+2` (Temurin) and `21.0.13.1` (Red_Hat-21.0.13.1.2-1).
- **The flag stays.** [`security.md`](../security.md#removing-the-flag) allows removing it, together with
  `Java4ShellMitigationTest`, now that all three pins carry a fixed JDK. It is kept for now as a second line of
  defence for deployments that override the Keycloak image or still run an earlier pull of a rebuilt tag. Its removal
  is a change of its own.
- Docs: `security.md` (status line, the image table, a note on images published before these pins) and
  `mcp-server.md` (the Keycloak version the stacks pin). `Java4ShellMitigationTest`'s Javadoc records the new pins.

### What's next

- Backport to the 6.7 line for 6.7.1 (labsai/EDDI#1500). That branch is on chart 2.14.1, so it moves to 2.14.2.
- Remove the flag from all five places and delete `Java4ShellMitigationTest` in one change, when the team decides
  the safeguard is no longer wanted.
