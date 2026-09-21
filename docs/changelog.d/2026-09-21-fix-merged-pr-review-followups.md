## 🧹 fix(docs,manager): answer five review findings left unanswered on merged PRs (2026-09-21)

**Repo:** EDDI (`fix/merged-pr-review-followups`) — follow-ups to PRs #737, #739, #752 and #772,
all long since merged, judged against `main` as it stands rather than against their diffs.

**What changed**

- **An avatar initial is one character again (PR #772).** `initialOf` in
  `ui/manager/src/lib/user-display.ts` upper-cased the matched letter and returned the
  whole result, but upper-casing can lengthen a letter: `"ß".toUpperCase()` is `"SS"` and
  the ligature `"ﬁ"` is `"FI"`. A user named ßeta Müller therefore got the three-character
  `"SSM"` in an avatar sized for two, and ﬁona ßauer would have got four. The function now
  keeps the first code point of the upper-cased result — `Array.from(...)[0]` rather than
  `[0]`, so the existing astral-plane case (`𝒜lice`) is unaffected. Three cases added to
  `user-display.test.ts`; mutation-checked against the old one-liner.
- **The NVIDIA GPU overlay is documented (PR #752).** `docker-compose.ollama-nvidia.yml`
  shipped naming itself in its own first line and appearing nowhere else in the
  repository — not the README, not a page under `docs/` — so the documented Ollama command
  did not enable GPUs and nothing said a second `-f` was needed. The README now carries the
  two-overlay command, lists the file among the available overlays, and states the two host
  prerequisites: **Docker Compose 2.30.0 or newer**, which is the release that introduced
  the `gpus` service attribute (older versions fail on it), and the NVIDIA Container
  Toolkit, without which the container starts and Ollama quietly runs on the CPU. The file
  itself gained the header every other overlay here has — including why it repeats the
  image tag (an overlay of a service the *base* file does not declare needs an image of its
  own, which `ComposeStackTest` enforces) and that the two tags must stay equal, because
  layering a lower one downgrades Ollama.
- **`ComposeStackTest` now sweeps the other direction.** It already checked that every
  compose file the README *names* exists; nothing checked that every compose file the
  repository *ships* is named anywhere, which is how the GPU overlay stayed invisible.
  README plus the pages under `docs/` count — that is where the Open WebUI stack and the
  MCP sidecar are explained — and the changelog does not, since an entry scrolls out into
  an archive nothing reads for current context.
- **A rotation count in an archived entry is corrected (PR #739).** The 2026-08-25 merge
  note in `docs/changelog/2026-08.md` said the rotation "moved the 18 oldest entries"; the
  archive went from 179 to 187 top-level entries in that commit, and the same commit
  removed 8 from the live file. It says 8.

**Declined, with the reason recorded on the thread:** the markdownlint MD018 finding on
PR #737 (two archived changelog lines that begin `#736 …`). MD018 is the reviewing bot's
linter, not this project's — there is no markdownlint config and no CI step running it —
and CommonMark requires a space after `#` for an ATX heading, so those lines render as
ordinary prose with GitHub's autolink on the PR reference, which is what they are meant to
be. Seven such lines exist across the archives; rewriting two of them would be churn on a
historical record for a rule the repository does not adopt.

**Verification:** `mvnw compile`, `ComposeStackTest`, and the repo-wide guards
(`ImportStyleTest`, `DocumentationLinksTest`, `DocumentationAccuracyTest`,
`ChangelogFragmentTest`, `BuildQualityGatesTest`); in `ui/manager`, `npm run lint`,
`npm run typecheck` and the `user-display` vitest file.
