## ⚙️ chore(ci): CodeRabbit reviews each PR once automatically, then on request (2026-11-24)

**Repo:** EDDI (`chore/coderabbit-review-on-request`)

### What changed and why

`.coderabbit.yaml` set `auto_incremental_review: false` and `drafts: false`.
Every PR still gets its first review automatically, stacked PRs into feature
branches included (`base_branches` is unchanged). Later pushes are reviewed
when someone comments `@coderabbitai review`, and a draft is reviewed once it
is marked ready.

The review allowance is small (in practice about two reviews per rate-limit
window), and there is no usage billing. With a review on every push (fixups, merges of `main` into a branch,
Auto-fix rounds) the allowance was spent on small follow-ups, and the reviews
that were needed came back "Review rate limited" and had to be requested by
hand anyway.

### Design decisions

- The first review stays automatic so a new PR cannot be forgotten.
- The merge rule in `AGENTS.md` is unchanged: no merge until CodeRabbit has
  reviewed the current head, and only the user can waive that for a PR they
  name. So after a fixup push someone now has to comment `@coderabbitai review`
  before merging; it no longer happens by itself.
- Drafts get no review until they are marked ready for review.
