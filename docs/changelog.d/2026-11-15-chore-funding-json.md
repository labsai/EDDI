## 💶 chore: funding.json manifest (2026-11-15)

**Repo:** EDDI (`chore/funding-json`)

### What changed

- `funding.json` (repo root): a [funding.json](https://fundingjson.org/) v1.1.0 manifest, the format FLOSS/fund and similar programmes read. It names EDDI Solutions (organisation, owner, `gregor.jarisch@eddi.solutions`, commercial website https://eddi.solutions), the EDDI project (Apache-2.0, https://eddi.technology, `https://github.com/labsai/EDDI`), one bank-transfer channel and two plans of any amount in EUR (one-time and monthly). Validated against `https://fundingjson.org/schema/v1.1.0.json` with `jsonschema`.

### Design decisions

- **No bank details in the repo.** The channel's `address` is empty and its description says to ask `gregor.jarisch@eddi.solutions`; an IBAN in a public file is scraped and never goes away.
- **Same host as the repository.** The manifest lives in `labsai/EDDI`, so `repositoryUrl` needs no `wellKnown` proof.

### What's next

- `https://eddi.solutions` (entity) and `https://eddi.technology` (project) are other hosts, so directories show those web pages as *unverified* until each site serves `/.well-known/funding-manifest-urls` containing `https://github.com/labsai/EDDI/blob/main/funding.json`; then add `wellKnown` to the matching `webpageUrl` entry.
