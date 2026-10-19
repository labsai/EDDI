## fix(test): two unit-test classes that failed on every Windows checkout (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/flaky-local-unit-tests`

Both failures reproduced on a clean `origin/main` and on no CI run: each is a test that
built its fixture in a form only Linux accepts. Neither is a product bug; in both cases
the product code under test behaved correctly and the fixture never reached it.

### What changed and why

- **`McpApiToolBuilderExternalRefTest.oversizedJsonSpecWithEscapedKeyIsStillScanned`.**
  `specReferencing` spliced a `@TempDir` path into a JSON string unescaped. On Windows
  that path is `C:\Users\…`, and `\U` is not a legal JSON escape, so the spec was not
  JSON at all. The cases the raw-text pass catches still passed; this one rests on the
  decoded-tree pass alone (its `$ref` key is Unicode-escaped), which could not build a
  tree and refused the spec as "could not be parsed" instead of reporting the external
  reference. `rejectUnsafeInlineRefs` failing closed there is correct. The helper now
  JSON-escapes backslashes, so every case reaches the reference it is about.
- **`DeploymentManifestsTest` — "secret generator — fail-closed classification"**
  (three PowerShell cases). The Windows `kubectl.cmd` stand-in recognised the existence
  probe with a bare `find`. A Maven started from Git Bash hands its PATH down, with
  Git's Unix `find` ahead of `System32\find.exe`; that one rejects the argument, the
  stub's `errorlevel 1` branch took the error for "not the probe", and every probe
  exited 0. Every stub mode therefore read as "a Secret already exists", so the
  NotFound path refused to install and the unreachable/forbidden paths aborted with the
  wrong message. `create-secrets.ps1` classified each answer it was given correctly
  (checked by hand against the stub with a PowerShell-launched PATH). The stub now
  calls `%SystemRoot%\System32\find.exe` by full path. CI's ubuntu runner never takes
  this branch, which is why it stayed green.

### Design decisions

- Fix the fixtures, not the code: the guard and the generator were right, and loosening
  either to accommodate a malformed fixture would have weakened a security check.
