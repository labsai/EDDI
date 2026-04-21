# Contributing to EDDI Manager

Thank you for your interest in contributing to EDDI Manager! This is the admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform. This guide will help you get started.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Building & Testing](#building--testing)
- [Code Style](#code-style)
- [Commit Convention](#commit-convention)
- [Pull Request Process](#pull-request-process)
- [What the CI Checks](#what-the-ci-checks)
- [Security](#security)

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code. Please report unacceptable behavior to the project maintainers.

## How Can I Contribute?

### 🐛 Reporting Bugs

- Use the [Bug Report](https://github.com/labsai/EDDI-Manager/issues/new) issue template
- Include steps to reproduce, expected vs actual behavior, and your environment details
- Check [existing issues](https://github.com/labsai/EDDI-Manager/issues) first to avoid duplicates

### 💡 Requesting Features

- Use the [Feature Request](https://github.com/labsai/EDDI-Manager/issues/new) issue template
- Describe the problem you're trying to solve, not just the solution

### 🔧 Code Contributions

1. Look for issues labeled [`good first issue`](https://github.com/labsai/EDDI-Manager/labels/good%20first%20issue) or [`help wanted`](https://github.com/labsai/EDDI-Manager/labels/help%20wanted)
2. Comment on the issue to let others know you're working on it
3. Follow the [Pull Request Process](#pull-request-process) below

## Development Setup

### Prerequisites

| Tool         | Version | Notes                                    |
| ------------ | ------- | ---------------------------------------- |
| **Node.js**  | 20+     | LTS recommended                          |
| **npm**      | 10+     | Bundled with Node.js                     |
| **Docker**   | Latest  | For backend services (optional)          |

### Getting Started

```bash
# 1. Fork the repository on GitHub

# 2. Clone your fork
git clone https://github.com/<your-username>/EDDI-Manager.git
cd EDDI-Manager

# 3. Install dependencies
npm install

# 4. Start the dev server
npm run dev
# Opens on http://localhost:3000

# 5. (Optional) Start EDDI backend via Docker
docker compose -f docker-compose.integration.yml up -d
```

If the EDDI backend is not available, the Manager automatically starts in **standalone mode** with mock data via [MSW](https://mswjs.io/).

### IDE Setup

**VS Code** (recommended):

- Install the ESLint and Prettier extensions
- The project includes `.editorconfig` for consistent formatting

**WebStorm / IntelliJ IDEA**:

- Import as an npm project
- Enable ESLint integration (Settings → Languages & Frameworks → JavaScript → Code Quality Tools → ESLint)

## Building & Testing

```bash
# Lint
npm run lint

# Type check
npm run typecheck

# Run unit/component tests (Vitest)
npm run test

# Run tests in watch mode
npm run test:watch

# Run E2E tests (Playwright)
npm run test:e2e

# Production build
npm run build
```

## Code Style

### General Rules

- **Language**: TypeScript 5 (strict mode) — no `any` unless absolutely necessary
- **Framework**: React 19 with functional components and hooks
- **Styling**: Tailwind CSS v4 — use logical properties (`ps-*`, `pe-*`, `ms-*`, `me-*`) for RTL support
- **State**: TanStack Query for server state, Zustand or `useState` for UI state
- **Never** use `pl-*`/`pr-*`/`ml-*`/`mr-*`/`left-*`/`right-*` — use logical properties for RTL support
- **Never** hardcode the API URL — always go through `ApiClient`

### i18n

- All user-facing strings must use `react-i18next`
- Add new keys to `src/i18n/locales/en.json` first, then propagate to all 10 other locale files
- Use inline fallbacks: `t("key", "Fallback")`

### What to Avoid

- MUI, Redux, or legacy React patterns
- `moment.js` — use native `Intl` or `date-fns`
- Mixing component exports with utility function exports in the same file

## Commit Convention

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): description

[optional body]

[optional footer]
```

### Types

| Type       | Use for                                 |
| ---------- | --------------------------------------- |
| `feat`     | New feature                             |
| `fix`      | Bug fix                                 |
| `docs`     | Documentation only                      |
| `test`     | Adding or updating tests                |
| `refactor` | Code change that neither fixes nor adds |
| `chore`    | Maintenance (deps, CI, configs)         |
| `perf`     | Performance improvement                 |

### Examples

```
feat(editors): add RAG vector store configuration panel
fix(chat): prevent duplicate messages on reconnect
docs(readme): add Keycloak setup instructions
test(agents): add agent import dialog coverage
chore(deps): bump React to 19.1
```

## Pull Request Process

### Workflow

1. **Fork** the repository and create a feature branch from `main`:

   ```bash
   git checkout -b feat/my-awesome-feature
   ```

2. **Make your changes** — keep PRs focused and reasonably sized

3. **Write tests** — new features require tests; bug fixes should include a regression test

4. **Run the full build** locally:

   ```bash
   npm run lint && npm run typecheck && npm run test && npm run build
   ```

5. **Push** and open a Pull Request against `main`

6. **Wait for CI** — all automated checks must pass before review

7. **Address review feedback** — push new commits, don't force-push over existing review

### PR Guidelines

- **One concern per PR** — don't mix refactoring with features
- **Write a clear PR description** using the template
- **Link the related issue** with `Closes #123`
- **Keep commits clean** — squash fixup commits before requesting review

## What the CI Checks

Every PR runs through these automated gates:

| Check          | What It Does                                       | Must Pass? |
| -------------- | -------------------------------------------------- | ---------- |
| **Lint**       | ESLint with `--max-warnings 0`                     | ✅ Yes     |
| **Type Check** | `tsc --noEmit` (full project type-check)           | ✅ Yes     |
| **Unit Tests** | Vitest with 630+ component tests                   | ✅ Yes     |
| **Build**      | Production build via `tsc -b && vite build`        | ✅ Yes     |
| **E2E Tests**  | Playwright UI tests with MSW mocks                 | ✅ Yes     |

## Security

- **Never commit secrets** — API keys, tokens, passwords
- **Report vulnerabilities privately** — see [SECURITY.md](SECURITY.md)
- For backend security concerns, see the [EDDI SECURITY.md](https://github.com/labsai/EDDI/blob/main/SECURITY.md)

## Questions?

- Open a [Discussion](https://github.com/labsai/EDDI/discussions) on the main EDDI repo for general questions
- Check the [EDDI documentation](https://docs.labs.ai/) for usage guides
- Browse [existing issues](https://github.com/labsai/EDDI-Manager/issues) for known topics

---

Thank you for helping make EDDI better! 🎉
