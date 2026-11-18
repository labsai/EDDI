## 🔒 fix(security): Java4Shell interim mitigation, -XX:-UseBASE64Intrinsics on every shipped JVM (2026-11-18)

**Repo:** EDDI · **Branch:** `fix/java4shell-base64-intrinsics`

### What changed and why

CVE-2026-114938 ("Java4Shell", Red Hat [RHSB-2026-018](https://access.redhat.com/security/vulnerabilities/RHSB-2026-018)) is memory corruption in the AVX-512 VBMI accelerated path of `java.util.Base64.Decoder`. With malformed input, an unauthenticated attacker can crash the JVM or run code in it on x86-64 hosts from Intel Ice Lake and AMD Zen 4 on. EDDI decodes such input before authentication: MIME encoded words and DKIM signatures of inbound mail, and webhook signature headers. Keycloak does as well. Fixed JDKs are 25.0.5.1 and 21.0.13.1. On 18 Nov, Red Hat had published neither a fixed UBI image nor a fixed RPM: the newest `ubi10/openjdk-25-runtime:1.24` build is the 11 Nov digest pinned here, and both it and the UBI repository carry `25.0.5.0.8`.

- `src/main/docker/Dockerfile`: `-XX:-UseBASE64Intrinsics` added to `JDK_JAVA_OPTIONS`, which survives an operator's `JAVA_OPTS` / `JAVA_OPTS_APPEND`.
- `src/main/docker/Dockerfile.demo`: the same flag on the bare `java` ENTRYPOINT (Temurin 25.0.5).
- Keycloak 26.8.0 (bundles OpenJDK 21.0.12.1): `JAVA_OPTS_APPEND=-XX:-UseBASE64Intrinsics` in `docker-compose.auth.yml`, `helm/eddi/templates/keycloak.yaml` (chart 2.14.2 → 2.14.3, `EXPECTED_CHART_VERSION` with it) and `k8s/overlays/auth/keycloak-statefulset.yaml`.
- New `Java4ShellMitigationTest` pins the flag in all five places.
- `docs/security.md`: new "JVM Advisory: Java4Shell" section, covering who is affected, what ships, the manual mitigation for older images, and how to check the running JVM. `docs/upgrading-from-6.5.md`: a note under "Changes after 6.7.0".

### Design decisions

- **`-XX:-UseBASE64Intrinsics`, not `-XX:UseAVX=2`.** It removes only the vulnerable path; `UseAVX=2` also drops every other AVX-512 optimisation, and a JVM on ARM64 refuses to start with it.
- **No `-XX:+UnlockDiagnosticVMOptions`.** In all three pinned images, `PrintFlagsFinal` reports `UseBASE64Intrinsics` as a product flag that takes effect from the command line.
- **Not changed:** `mise.toml` (Temurin 25.0.4, for development only; no fixed Temurin build was available) and the integration-test Keycloak compose files under `ui/manager/` (CI only, never exposed).

### What's next

When Red Hat ships a fixed `openjdk-25-runtime` digest, and Keycloak an image with a fixed JDK: move both pins, remove the flag from all five places, and delete `Java4ShellMitigationTest` in the same change. Bump `mise.toml` once Temurin 25.0.5.1 is out.
