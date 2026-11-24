## 📝 docs(branding): a social preview image for the repository (2026-11-21)

**Repo:** EDDI (`docs/social-preview`)

### What changed and why

The image GitHub shows when someone shares a link to the repository was still
the old README banner, whose tagline ("The Enterprise AI Orchestrator")
matched neither the README nor the repository description, both changed in the
README redesign. A social preview is read at thumbnail size in chat apps and
feeds, so it is its own design rather than a crop of the README banner: large
type, and no code, which is unreadable that small.

- [`branding/readme-banner/social-preview.html`](../../branding/readme-banner/social-preview.html)
  is the source: the wordmark, "The open-source AI agent management system",
  "Works with Claude, Cursor and any MCP client", the repository URL, and four
  tiles for run, limit, approve and record. It shares its colours and wording
  with `banner.html`.
- [`ui/manager/scripts/capture-readme-images.mjs`](../../ui/manager/scripts/capture-readme-images.mjs)
  renders it with `--only social` at 1280×640, GitHub's size, and device scale
  factor 1 so the file stays well under GitHub's 1 MB limit.
- `screenshots/eddi-social-preview.png` is the rendered image (about 240 KB).

### What's next

GitHub has no API for the social preview, so the image is uploaded by hand in
the repository's Settings, General, Social preview. Nothing in the README
references it.
