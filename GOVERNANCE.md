# EDDI Governance

## Project Leadership

EDDI is maintained by [Labs.ai](https://labs.ai) under the stewardship of the following roles:

### Project Lead

- **Gregor Jarisch** ([@ginccc](https://github.com/ginccc)) — Founder, architect, and primary maintainer. Responsible for roadmap, architecture decisions, and release management.

### Core Maintainers

Core maintainers have commit access and review authority over all areas of the codebase.

| Maintainer | GitHub | Focus Areas |
|------------|--------|-------------|
| Gregor Jarisch | [@ginccc](https://github.com/ginccc) | Full stack, architecture, security |
| Roland Pickl | [@rolandpickl](https://github.com/rolandpickl) | Backend, testing, code review |

## Decision-Making Process

1. **Day-to-day decisions** (bug fixes, minor improvements) are made by any core maintainer.
2. **Significant changes** (new features, architecture changes, dependency upgrades) require a pull request with at least one review from another core maintainer.
3. **Strategic decisions** (roadmap direction, major version releases, licensing changes) are made by the Project Lead after discussion with core maintainers.

## Contribution Process

All contributions follow the process described in [CONTRIBUTING.md](CONTRIBUTING.md):

1. Open an issue or discussion describing the proposed change.
2. Fork the repository and create a feature branch.
3. Submit a pull request targeting `main`.
4. At least one core maintainer must review and approve before merge.
5. CI checks (build, test, security scans) must pass.

## Code Review Policy

- All pull requests require at least **one approval** from a core maintainer who is not the author.
- Security-sensitive changes (authentication, secrets handling, HTTP clients, input validation) require review from a maintainer with security focus.
- AI-assisted code (from coding agents) follows the same review process as human-authored code.

See [docs/code-review-standards.md](docs/code-review-standards.md) for detailed review criteria.

## Security Policy

Security vulnerability reporting follows [SECURITY.md](SECURITY.md). The Project Lead is the security response coordinator.

## Releases

- Releases are tagged from `main` using semantic versioning (`v6.0.0`, `v6.0.1`, etc.).
- Release candidates use the `-RC` suffix (`v6.0.0-RC1`).
- Docker images are signed with Cosign (keyless OIDC) and published to Docker Hub.
- All releases include an SBOM (Software Bill of Materials) generated during CI.

## License

EDDI is licensed under the [Apache License 2.0](LICENSE).

## Amendments

This governance document may be updated by the Project Lead via a pull request with review from at least one other core maintainer.
