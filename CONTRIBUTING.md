# Contributing to EDDI

Thank you for your interest in contributing to E.D.D.I! This guide will help you get started.

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

- Use the [Bug Report](https://github.com/labsai/EDDI/issues/new?template=bug_report.yml) issue template
- Include steps to reproduce, expected vs actual behavior, and your environment details
- Check [existing issues](https://github.com/labsai/EDDI/issues) first to avoid duplicates

### 💡 Requesting Features

- Use the [Feature Request](https://github.com/labsai/EDDI/issues/new?template=feature_request.yml) issue template
- Describe the problem you're trying to solve, not just the solution
- Consider how it fits with EDDI's [project philosophy](docs/project-philosophy.md)

### 🔧 Code Contributions

1. Look for issues labeled [`good first issue`](https://github.com/labsai/EDDI/labels/good%20first%20issue) or [`help wanted`](https://github.com/labsai/EDDI/labels/help%20wanted)
2. Comment on the issue to let others know you're working on it
3. Follow the [Pull Request Process](#pull-request-process) below

## Development Setup

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **Java (JDK)** | 25 | [Eclipse Temurin](https://adoptium.net/) recommended |
| **Maven** | 3.9+ | Bundled via `mvnw` wrapper — no install needed |
| **MongoDB** | 6.0+ | Local instance or Docker |
| **Docker** | Latest | For integration tests and container builds |

### Getting Started

```bash
# 1. Fork the repository on GitHub

# 2. Clone your fork
git clone https://github.com/<your-username>/EDDI.git
cd EDDI

# 3. Start MongoDB (via Docker)
docker run -d --name mongodb -p 27017:27017 mongo:7

# 4. Run in dev mode (hot-reload enabled)
./mvnw compile quarkus:dev

# 5. Open in browser
# http://localhost:7070
```

### IDE Setup

**IntelliJ IDEA** (recommended):
- Import as Maven project
- Enable annotation processing (Settings → Build → Compiler → Annotation Processors)
- Install the Quarkus plugin for dev mode integration

**VS Code**:
- Install "Extension Pack for Java" and "Quarkus" extensions

## Building & Testing

```bash
# Compile only
./mvnw clean compile

# Run unit tests
./mvnw test

# Full build: compile + unit tests + package
./mvnw clean verify -DskipITs

# Integration tests (requires Docker)
./mvnw verify

# Build Docker image
./mvnw clean package -DskipTests '-Dquarkus.container-image.build=true'
```

## Code Style

### General Rules

- **Language**: Java 25 — use modern Java features (records, sealed classes, pattern matching)
- **Framework**: Quarkus + CDI — prefer `@Inject` over manual instantiation
- **Line length**: 120 characters max
- **Checkstyle**: Run `./mvnw validate` to check style before submitting
- **No `@author` tags** in new code — Git history tracks authorship
- **No `System.out.println`** — use `java.util.logging` or Quarkus logging

### Architecture Principles

EDDI follows a strict [project philosophy](docs/project-philosophy.md). Key points for contributors:

- **Configuration is logic, Java is the engine** — bot behavior belongs in JSON, not Java code
- **Security as architecture** — no `eval()`, no `ScriptEngine`, no dynamic code execution
- **Stateless tasks** — `ILifecycleTask` implementations must be stateless singletons
- **URL validation** — all external calls must use `UrlValidationUtils.validateUrl()`

### What to Avoid

- Hardcoding model names, API endpoints, or prompt templates in Java
- Introducing dynamic scripting engines (GraalJS, Nashorn)
- Adding `@JsonTypeInfo(use=Id.CLASS)` for untrusted payloads
- Storing unbounded maps without TTL

## Commit Convention

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): description

[optional body]

[optional footer]
```

### Types

| Type | Use for |
|------|---------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | Code change that neither fixes nor adds |
| `chore` | Maintenance (deps, CI, configs) |
| `perf` | Performance improvement |
| `security` | Security fix |

### Examples

```
feat(langchain): add Gemini 2.5 Flash support
fix(mcp): prevent NPE when conversation memory is empty
docs(httpcalls): add SSRF protection examples
test(schedule): add CronDescriber edge case tests
chore(deps): bump Quarkus to 3.33.0
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
   ./mvnw clean verify -DskipITs
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

| Check | What It Does | Must Pass? |
|-------|-------------|------------|
| **Build + Tests** | `mvnw clean verify` with Java 25 | ✅ Yes |
| **CodeQL** | Security scanning (injection, hardcoded creds, etc.) | ✅ Yes |
| **Dependency Review** | Blocks vulnerable or incompatibly-licensed deps | ✅ Yes |
| **CodeRabbit** | AI code review with line-by-line feedback | Advisory |
| **Checkstyle** | Java code style validation | ⚠️ Warnings |
| **JaCoCo** | Code coverage report | 📊 Report only |

## Security

- **Never commit secrets** — API keys, tokens, passwords
- **Use Vault references** — `${vault:key-name}` for sensitive configuration
- **Report vulnerabilities privately** — see [SECURITY.md](SECURITY.md)
- **Read the security guide** — [docs/security.md](docs/security.md)

## Questions?

- Open a [Discussion](https://github.com/labsai/EDDI/discussions) for general questions
- Check the [documentation](https://docs.labs.ai/) for usage guides
- Browse [existing issues](https://github.com/labsai/EDDI/issues) for known topics

---

Thank you for helping make EDDI better! 🎉
