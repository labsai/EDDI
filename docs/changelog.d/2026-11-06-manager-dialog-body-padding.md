## Manager: dialog bodies no longer touch the dialog border (2026-11-06)

**Repo:** EDDI (`ui/manager`) · **Branch:** `fix/manager-dialog-body-padding`

**What changed.** The "This agent is not granted its vault keys" dialog showed its
title bar with padding but its body flush against the dialog's left and right border:
the key notice box, the "Add … to the grant?" question, the "allow every agent"
checkbox and the footer buttons all sat on the edge.

**Root cause.** `AccessibleDialog` (`ui/manager/src/components/ui/accessible-dialog.tsx`)
pads only its own header (`p-5`); the content slot is rendered edge to edge on purpose,
so full-bleed bodies (the version diff editor, toolbar strips) can reach the border. Every
caller is expected to pad its own content, and 13 of the 18 call sites do (`p-5`, a few
`p-6`). Five did not:

- `ui/manager/src/components/secrets/grant-required-dialog.tsx` (the reported dialog)
- `ui/manager/src/components/feedback/ratings-table.tsx` (internal-note dialog)
- `ui/manager/src/components/quotas/team-budget-dialogs.tsx` (adjust-cap and transfer dialogs)
- `ui/manager/src/pages/user-conversations.tsx` (Create Binding dialog)

Each now wraps its body in `p-5`, matching the header. The convention is written into
the `children` doc comment of `AccessibleDialog`.

**Design decision.** Fixed at the call sites, not in the primitive: padding the content
slot by default would double-pad the 13 dialogs that already pad themselves and break the
full-bleed ones. The shared `AlertDialog` pads its whole content (`p-6`) and was fine; the
hand-rolled modals and sheets were audited and each pads its own sections. The Chat UI has
one inline confirm strip, which is padded.
