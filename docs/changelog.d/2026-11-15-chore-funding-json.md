## 💶 chore: funding.json manifest (2026-11-15)

**Repo:** EDDI (`chore/funding-json`)

### What changed

- `funding.json` (repo root): a [funding.json](https://fundingjson.org/) v1.1.0 manifest, the format FLOSS/fund and similar programmes read. It names EDDI Solutions (organisation, owner, `gregor.jarisch@eddi.solutions`, commercial website https://eddi.solutions), the EDDI project (Apache-2.0, https://eddi.technology, `https://github.com/labsai/EDDI`), one bank-transfer channel and two plans of any amount in EUR (one-time and monthly). Validated against `https://fundingjson.org/schema/v1.1.0.json` with `jsonschema`.

### Design decisions

- **No bank details in the repo.** The channel's `address` is empty and its description says to ask `gregor.jarisch@eddi.solutions`; an IBAN in a public file is scraped and never goes away.
- **Same host as the repository.** The manifest lives in `labsai/EDDI`, so `repositoryUrl` needs no `wellKnown` proof.
- **The web pages are on other hosts**, so both `webpageUrl` entries carry a `wellKnown` URL: `https://eddi.solutions/.well-known/funding-manifest-urls` (entity) and `https://eddi.technology/.well-known/funding-manifest-urls` (project). Each file lists `https://github.com/labsai/EDDI/blob/main/funding.json` and is added in `labsai/website-eddi-solutions` and `labsai/EDDI-LABS-AI-Website` (branch `chore/funding-manifest-urls` in both).

### What's next

- The two URLs verify only once both website PRs are deployed and this one is merged, since the listed manifest URL points at `main`.
