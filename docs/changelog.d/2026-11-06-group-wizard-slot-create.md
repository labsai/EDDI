## 🐛 fix(manager): per-slot agent creation in the group wizard cannot overwrite or orphan agents (2026-11-06)

**Repo:** EDDI (`fix/group-wizard-slot-create`)

### What was wrong

"Create Agent" on a member card of the Group Setup Wizard provisions and deploys
that agent while the person keeps working. Its write-back rebuilt the whole
member list from the render in which the button was clicked, by index. So with
two cards created at once, whichever finished second set the other card back to
"not created" (its agent id gone), and the final Create then provisioned and
deployed a second agent for that card. The first stayed deployed with nothing
pointing at it. An edit to another card made while one was provisioning was
reverted, and removing a card above a provisioning one shifted the indexes, so
the new agent was written into the wrong card. The moderator's write-back spread
the moderator as it was at the click, reverting edits made meanwhile.

Three more ways in: the final Create stayed enabled while a card was still
provisioning (provisioning that card a second time); a card's Remove and type
toggle stayed enabled while its own agent was being created; and during the
final Create, Back and the step indicator still worked, so the person could edit
members that the Create then overwrote with its own click-time copy.

### What changed

- [`group-wizard.tsx`](../../ui/manager/src/pages/group-wizard.tsx): every member
  slot carries a stable client-side `id` (a module counter, not
  `crypto.randomUUID()`, which is missing outside a secure context; it is never
  sent, since the save maps slots to `GroupMember` field by field). The wizard's
  `update` also accepts a function of the current state, and every member and
  moderator write in the Members step goes through it, keyed by id. Cards are
  keyed by id. The final Create is disabled (and `handleCreate` returns) while
  any card is provisioning; a card's Remove and type toggle, and the moderator's
  Remove, are disabled while that agent is being created; Back and the step
  buttons are disabled during the final Create. The per-slot buttons get
  `gw-create-agent-<n>` and `gw-create-moderator` test ids.
- [`create-group-dialog.tsx`](../../ui/manager/src/components/groups/create-group-dialog.tsx):
  removed the `selectedTemplate` state, whose value was never read.
- New [`group-wizard-slot-create.test.tsx`](../../ui/manager/src/pages/__tests__/group-wizard-slot-create.test.tsx):
  six cases (two cards at once, an edit and a removal during provisioning, the
  locked controls, a moderator edit across a failed create, navigation locked
  during the final Create). Each fails on the previous code.

### Design decisions

- **Functional updates over a ref to the latest state.** A ref would also fix the
  stale read, but writes would still race each other between renders; a
  `setState` updater always applies to the state React holds.
- **Lock navigation during the final Create rather than make its write-back
  id-keyed too.** The review step renders no editors, so once Back and the steps
  are locked nothing can change the members under it, and its retry-safety logic
  stays as it is.
