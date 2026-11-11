## 🐛 fix(manager): Agent Health review fixes — later-phase controls, honest wording, safer inputs (2026-11-11)

**Repo:** EDDI (`feat/agent-health-ui-wiring`)

### What changed and why

Review findings on the Agent Health UI (PR #1390), each checked against the code and against
[`planning/agent-health-api.md`](../../planning/agent-health-api.md) before it was acted on.

- **Profile, report links and export are hidden against a real backend.** The backend has none of those endpoints yet
  (H4, H4b, H5), so the buttons could only ever answer 404. `laterHealthPhasesAvailable()` in `components/health/api.ts`
  offers them only while the mock API is active (`__EDDI_MOCK_ACTIVE__`); flip it when the phases land.
- **`#health` opens a mounted, collapsed panel.** The link only scrolled to a panel that was already on the page; it now
  opens it (which enables the query) on every navigation to the hash.
- **"Not significant" no longer claims normal variation.** `significant: false` also means too few samples in either
  period (§12), so the delta reads "not significant, or too little data". The sparkline's label names its range
  ("between min and max") instead of implying a movement from one to the other. §14 of the contract notes follow.
- **Series failures are shown.** A section whose trends fail to load says so, with a retry; before, the sparklines simply
  went missing.
- **The summary has no placeholder data.** Another period, version or origin set shows the loading state instead of the
  previous selection's numbers under the new filters.
- **Marker list dates are UTC days**, the day the sparkline places each marker on.
- **Compare pickers.** The older-version list is taken from below the chosen newer version, so the derived default is
  always selectable even at the bottom of the 25-version cap.
- **Profile editor.** Each threshold row has a remove button; save and reset are disabled, and their handlers refuse,
  while either is in flight; a lone separator before three digits ("1,000", "1.000") is read in the app language's
  convention instead of always as a decimal.
- **Report links.** Revoking the link that was just created takes its URL off the screen (only on success).
- **The export note is visible text**, linked to the buttons with `aria-describedby`, not only a tooltip.
- **Overview.** "Statuses are not calculated yet" shows only when the unfiltered list says so (every `status` is `null`),
  not for any empty status-filter result.
- **Mocks follow the backend more closely:** summary and series ranges follow `period`; sessions filter by `version` and an
  outcome-less row never matches an outcome or evidence filter; the portfolio sorts by name and traffic before paging;
  escalation and abandonment have no thresholds and `outcomeCoverage` uses green ≥ 0.8 / red ≤ 0.5 (H3); the provisional
  resolved session is `self_reported`, not `external_event`, which H3 cannot record.
- Two new and two reworded strings in all 11 locales.

### Design decisions

- Gating the later-phase controls on the mock flag rather than probing the endpoints: a probe costs a request per panel and
  cannot tell a missing route from a missing agent; the flag is one line to change per phase.
