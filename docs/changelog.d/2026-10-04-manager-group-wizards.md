## Manager: group and workforce wizards, group templates and workspaces UX fixes (2026-10-04)

**Repo / branch:** EDDI, `fix/manager-group-wizards` (UX review stream C1; Manager only, no backend change).

**Group wizard (`pages/group-wizard.tsx`)**

- **No duplicate agents on retry.** When creating the moderator failed, the wizard returned without
  writing the members it had already provisioned back into its state, so clicking Create again
  provisioned and deployed every member a second time. Every failure path now persists members and
  moderator progress (one `abort` helper). Regression test `group-wizard-ux.test.tsx` fails (12 setup
  calls instead of 7) with the fix reverted.
- **API keys and providers.** A new advisor on a provider that needs a key is now blocked on the
  Members step with a per-member sentence (the same `providerNeedsKey` rule the Workforce wizard
  uses), instead of the backend rejecting the setup request after earlier members were deployed. Keys
  for keyless providers are no longer sent. A "same provider and key for all new agents" block
  applies one provider/model/key to every advisor still to be created. Provider pickers (members,
  moderator, bulk, and the Workforce wizard's team builder) offer only providers
  `isProvisionableBySetup` accepts, so Vertex AI is no longer offered.
- **Why Next is disabled.** The wizard lists what is missing under the step (name, HITL phases and
  timeout, style, member count, each member's name / principal id / group / agent / key, turn
  timeout). The same list gates Next.
- **Unsaved work.** Both wizards guard reload and tab close once anything is configured, and ask
  before Back, Cancel and "Back to groups" discard it (`useLeaveConfirm`, built on
  `useUnsavedChangesGuard` plus the existing `UnsavedChangesDialog`). After a confirmed leave or a
  successful create the guard is released first, so a router-level block never prompts twice.
- **Stepper and styles.** Visible step titles (sm and up), per-step `aria-label`, `aria-current`.
  Style cards are a `radiogroup` of `role="radio"` / `aria-checked`, carry a "best for" line, and the
  selected style shows its full flow. Max Rounds explains what a round is and shows an estimated call
  count (members x rounds, plus one for a moderator).
- **Turn timeout** is a number plus a minutes/hours/days picker that serialises to ISO-8601
  (`DurationField`, `lib/iso-duration-parts.ts`).
- **i18n and labels.** Default member and moderator prompts and placeholders are translated and no
  longer say "a Engineering expert"; member name, role, prompt and model inputs have labels; the
  Agent/Group type toggle is localised and exposes `aria-pressed`. The Guided Setup card on the Groups
  page has group-specific copy.
- **Two template systems** are now named: the wizard's client presets are "starter presets" with a
  link to the packaged Group Templates, and the templates page links back to the wizard. Role chips
  are humanised and de-duplicated (`Forecasting x4`, `Devil advocate`, `lib/role-labels.ts`).

**Other pages**

- **Group templates:** the selected template is a `?template=` search param, so browser Back returns
  to the gallery, a reload keeps the selection, and the error view's Back link goes somewhere
  different from where the user is.
- **Group workspace:** subject, description and prompt-template inputs are labelled; `P{n}` has a
  tooltip and the priority field says higher numbers are picked first; the cadence time zone is a
  select defaulting to the browser zone instead of free text defaulting to UTC.
- **Workspaces:** deleting a variable asks for confirmation, like deleting a secret.

**Decisions / deliberately skipped**

- Backlog task edit/delete was **not** added: there is no per-task update or delete endpoint on REST or
  MCP (`lib/api/group-workspace.ts` documents it). The page now says tasks cannot be edited or removed
  once added. Needs a backend change if wanted.
- The Workforce wizard stepper already has visible labels, `role="list"` and `aria-current`; nothing
  to change there.
- The key requirement is checked client-side only for AGENT slots in Create New mode; a slot already
  created by an earlier attempt is not re-validated.
- Existing group wizard tests that walk to the Review step now apply a bulk key first, because the
  Members step no longer lets a keyless Anthropic advisor through.
