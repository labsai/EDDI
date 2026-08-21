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

- Open a [new issue](https://github.com/labsai/EDDI-Manager/issues/new)
- Include steps to reproduce, expected vs actual behavior, and your environment details
- Check [existing issues](https://github.com/labsai/EDDI-Manager/issues) first to avoid duplicates

### 💡 Requesting Features

- Open a [new issue](https://github.com/labsai/EDDI-Manager/issues/new)
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

### Mutation testing — does the suite notice when code breaks?

Coverage says which lines *ran*. It cannot say whether anything would have
*complained*. Those are different questions, and an audit of this repo found
the gap was real: tests that asserted an element existed rather than that a
behaviour happened, and eleven that could not fail at all.

```bash
# The guarded scope — operator security guards, approval logic, version compare
npm run test:mutation
```

```bash
# Just the file you are working on (much faster)
npm run test:mutation:file -- src/lib/operator/gate-guard.ts
```

Three things about that second command, all of which will bite otherwise:
it inherits `thresholds.break`, so pointing it at a file that is *already*
below the floor exits 1 without you having broken anything (`write-canary.ts`
is at 75%); `--mutate` **replaces** the config's list rather than narrowing it,
so the `__tests__` exclusion does not apply; and multiple files must be
comma-separated, because a second space-separated path is parsed as the
config-file argument instead.

A **survived** mutant is one the suite did not notice: the code was changed and
every test still passed. Usually that means the line is unverified, whatever the
coverage report says. Sometimes it means the mutant was *equivalent* — the
change did not alter behaviour, so no test could have caught it and none should
try. The audit that started this work hit one: dropping `204` from
`status === 202 || status === 204` changes nothing, because a spec-compliant 204
has an empty body and falls through to the same early return. Read a survivor as
a question rather than a verdict, and check which kind it is before writing a
test for it. The HTML report lands in `reports/mutation/`.

Budget about **25 minutes**: three CI runs of the full scope took 19, 23 and 24
minutes. Roughly 2m30s of each is a single instrumented replay of the suite
before the first mutant runs, so Stryker can learn which tests reach which code.
Locally it depends entirely on what else your machine is doing — the same work
has taken 51 minutes here. That first step is why the scope is kept small: it
grows with how widely the mutated files are imported, not with how many of them
there are.

Two things are deliberately **not** measured, each argued in
`stryker.config.json` with the measurement that decided it:

- **static mutants** — module-level constants. Stryker cannot swap one in
  without reloading the module, so each costs a full suite restart; measured,
  they were 14% of the mutants and 98% of the runtime.
- **`api-client.ts`** — 75 importers, so virtually every component test
  executes it and each of its mutants replays most of the suite.

(Tests themselves — `src/**/__tests__/**` — are excluded for the obvious
reason.) Neither is unimportant; both are guarded by ordinary unit tests
instead.

There was a third. `system-prompt.ts` was excluded on the argument that it is
mostly English prose in template literals, and that the only test able to kill
such a mutant is one pinning the exact wording. Measuring it disproved that:
it scores **76.60%**, because `system-prompt.test.ts` already asserts on
substrings and on structure — that the rules are numbered 1..4 contiguously,
for one — and those kill a blanked literal without pinning a sentence. It is in
scope now, for about a minute of runtime. Worth remembering when you reach for
the next exclusion: that was the one arrived at by reasoning rather than
measurement, and it was the one that turned out to be wrong.
tests instead.

CI runs this three ways: on a PR that touches the guarded scope, the tests that
cover it, or the config that decides what it measures; weekly on a schedule;
and on demand via `workflow_dispatch`.

`package.json` and `package-lock.json` are deliberately **not** triggers.
`renovate.json` pins devDependencies, so every bump edits both, and minor and
patch bumps automerge — listing either would put ~20 minutes in front of nearly
every Renovate PR. A dependency bump changing behaviour is exactly the drift the
weekly run exists to catch, and an hour on every dependency PR is how a job like
this gets switched off instead.

Do **not** make this job a required status check. A path-filtered workflow that
does not run reports neither success nor failure, so every PR that leaves the
guarded scope alone — which is most of them — would sit blocked on a check that
is never going to arrive.

`thresholds.break` is a ratchet: raise it when the score rises, never lower it
to make a build pass. A drop means a test stopped noticing something it used
to notice.

It works. Its first run found `blocked-calls.ts` — the single place deciding
which tool calls the Manager refuses, used by all three approval surfaces —
scoring **0%**, with two thirds of its mutants never executed by any test.

### Running E2E on a busy machine

`PORT` isolates a run, exactly as it does for `npm run dev`:

```bash
PORT=3100 npm run test:e2e
```

Without it, Playwright's `reuseExistingServer` will happily drive whatever dev
server is already on port 3000 — another worktree's, most likely — and every
test fails for reasons that have nothing to do with your branch.

### The `ui` tier always uses mock data

`src/main.tsx` normally decides between the real API and MSW by probing the
backend at startup. That makes the "no backend" tier depend on whether you
happen to have EDDI running: with a backend up it silently drove the real API,
and any assertion written against a fixture value failed for an unrelated
reason — or worse, passed while validating real data.

So the Playwright `ui` project seeds `eddi-force-mocks=true` into localStorage
via `storageState`, and `main.tsx` honours that ahead of the probe. It is
development-only (`import.meta.env.DEV`), so it cannot reach a production build.

If you ever set it by hand in your own browser, the mock-data banner is your
clue; clear the key to go back to probing:

```js
localStorage.removeItem("eddi-force-mocks")
```

The `integration` and `fullstack` tiers deliberately do **not** set it — they
want the real backend.

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
- Use inline fallbacks: `t("key", "Fallback")` — but treat the fallback as a safety net, **not** a substitute for the key. `npm run i18n:check` fails on any key that never reaches `en.json`, because a fallback is indistinguishable from a translation when you are reading the code
- One key, one English string. Calling the same key with two different defaults means one call site silently renders the other's text

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
- **Write a clear PR description** — what changed, why, and how you verified it
- **Link the related issue** with `Closes #123`
- **Keep commits clean** — squash fixup commits before requesting review

## What the CI Checks

Every PR runs through these automated gates:

| Check          | What It Does                                          | Must Pass? |
| -------------- | ----------------------------------------------------- | ---------- |
| **Audit**      | `npm run audit:prod` — production advisories only      | ✅ Yes     |
| **Lint**       | ESLint over `src/` and `e2e/`, `--max-warnings 0`      | ✅ Yes     |
| **i18n**       | `npm run i18n:check` — locale ↔ code drift             | ✅ Yes     |
| **Type Check** | `tsc -b` — the app, the node configs, AND `e2e/`       | ✅ Yes     |
| **Unit Tests** | Vitest, with coverage thresholds enforced              | ✅ Yes     |
| **Build**      | Production build via `tsc -b && vite build`            | ✅ Yes     |
| **E2E Tests**  | Playwright UI tests with MSW mocks                     | ✅ Yes     |

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
