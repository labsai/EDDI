## fix(manager): channel detail keeps edits typed while a save is in flight (2026-11-12)

**Repo:** EDDI (`fix/channel-detail-edits-during-save`)

### Why

The fields of a channel stay editable while the save request runs; only Save is disabled. A
successful save moves the URL to the new version, and the new `id@version` key re-seeded the
draft from the server copy, so anything typed between clicking Save and the reply vanished. The
page also went clean, so the unsaved-changes guard no longer fired.

### What changed

`handleSave` records the draft it sent and, once the reply arrives, compares it with the latest
draft. The baseline always moves to what was stored. If the form changed meanwhile, the draft is
kept, the new version is marked as already seeded so the seeding effect leaves it alone, and the
page stays dirty with the newer edit; the next save goes against the new version. A save with no
further typing behaves as before, and a navigation to another channel or version still seeds
from that one.

### Relation to #1358

`connection-detail.tsx` gets the same fix there (a `keepDraftFor` skip of the version switch).
This is a minimal local equivalent with the same semantics; the two should converge on one shared
helper once that PR has merged.

### Files

- `ui/manager/src/pages/channel-detail.tsx`
- `ui/manager/src/pages/__tests__/channel-detail-dirty-labels.test.tsx`
