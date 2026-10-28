## chore(release): attest SHA256SUMS with SLSA provenance (2026-10-24)

**Repo:** EDDI · **Branch:** `chore/release-provenance-attestation`

The OpenSSF Scorecard Signed-Releases check reported 6.6.0 as signed (the cosign bundle
`SHA256SUMS.sigstore.json`) but without provenance. The image job already attested its
digest with `actions/attest-build-provenance`; the release job, which publishes the
installer checksum list, did not attest anything.

- [`ci.yml`](../../.github/workflows/ci.yml) — the `release` job gets `attestations: write` and a
  `Generate SLSA provenance attestation (SHA256SUMS)` step after the cosign signature, using the
  same pinned action as the image job. It runs before the GitHub Release is created, so a failed
  attestation publishes nothing.
- Verify a release with `gh attestation verify SHA256SUMS --repo labsai/EDDI`.
- Applies to tags cut after this merges. 6.6.0 stays without provenance; it cannot be attested
  retroactively.
- Open question: Scorecard's check may look for a `.intoto.jsonl` release asset rather than the
  attestation store. Confirm on the next tag; if it still warns, the fallback is the
  `slsa-framework/slsa-github-generator` generic generator, which emits that asset.
