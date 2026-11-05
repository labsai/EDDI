## ✨ feat(groups): team lessons - sharing between teams and cost-capped consolidation (2026-11-03)

**Repo:** EDDI (`feat/team-learning-lessons`), stacked on `feat/team-learning-loop` (PR #1264), which sits on the unmerged
`feat/operator-mcp-integration`

### What changed and why

A team's retro lessons were visible only inside their own group, FIFO-evicted at `maxStoredLessons` and never consolidated.
Both are now possible, **off unless the group has a `lessonConfig`**:

- **Cross-team lessons.** `lessonConfig.shareWith` (owner) and `readFrom` (reader), consent on both sides like a handoff;
  `GET /groupstore/groups/{groupId}/lessons` and `…/lessons/shared` (which also returns `notShared`, the owners that did not
  grant). The reading team's RETRO phase quotes the shared lessons after its input, bounded, labelled as other teams' words
  (the hook added for outcomes in #1264 now composes both).
- **Lesson consolidation.** [`LessonConsolidationService`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/LessonConsolidationService.java)
  merges lessons that say the same thing and retires a lesson a later outcome contradicted. It rides the schedule machinery
  (`ScheduleFireExecutor` dispatches on the `lessonType` marker, as for Dream and team cadences; `PUT/DELETE
  …/lessons/consolidation/schedule`), reuses Dream's summarization call and cost arithmetic, and checks `maxCostPerRun` **before**
  the call against a worst case (dropping lessons until it fits, making no call when it cannot). A retirement must cite outcome
  ids the model was shown. Every change is conditional on the lesson being unchanged; the removed text, reason and evidence go
  to the new `group_lesson_history` store (MongoDB and PostgreSQL) first. `POST …/lessons/consolidation/run?dryRun=true`
  previews.
- **Manager.** A *Team lessons* card on the workspace page: own lessons, shared lessons with the owners that did not grant,
  consolidation state with schedule / preview / run, and the history; a line on the group config panel. `groupLessons.*` in all
  11 locales.
- **Platform Operator.** Read-only grants for the four lesson reads and a prompt section; **revision 55**. Not granted:
  scheduling, running or removing the consolidation (it rewrites a team's memory and spends budget).

### Design decisions

- A separate service rather than extending `DreamService`: Dream consolidates one *user's* memories, these are a *team's*, and
  the grouping, the evidence requirement and the history differ. The call and the cost arithmetic are shared.
- The cap is a hard pre-check, not Dream's soft "checked before each call". One call per run makes the soft form a no-op.
- Retirement without evidence is refused rather than trusted: a model asserting a lesson is wrong is not an outcome.
- History is a store of its own, not a flag on the memory entry, so the live set stays what members recall.

### Not in this PR

FIFO eviction in `RetroEngine` still drops the oldest lesson without recording it in the history. Sharing is read-only: a reader
cannot write to another team's lessons.

Docs: [`docs/team-learning-loop.md`](../team-learning-loop.md#lessons).

### Review follow-ups

- `RestScheduleStore` refuses the `lessonType=lesson_consolidation` marker in a create or update body (an update may only
  echo its own row's marker), and an update of a stored consolidation schedule needs EDIT on its group. A forged marker
  let anyone who could create a schedule consolidate a team they cannot edit. ZIP import goes through the same guard; the
  MCP tool cannot set metadata.
- A merge rewrites the kept lesson with `replaceIfUnchanged` instead of an unconditional `upsert`; losing that race skips
  the merge (`skipped_changed`) and drops no duplicate.
- A permanent group delete also removes the `lesson-consolidation-<groupId>` schedule.
- The workspace page shows the lessons panel from the group's current version, not the URL's.
- Docs: `maxCostPerRun` is an estimate, not a hard ceiling (the answer length is not enforced); history keeps the newest
  1,000 rows per team; an owner absent from both shared lists may still have lessons outside the cap.

### Review follow-ups (second round)

- Merged `feat/team-learning-loop` (which had `main` merged in): the full-metrics dashboard takes the base layout with the
  lesson-consolidation panel beside the vote tie-break panel; operator **revision 58** (the base, after a second merge
  that brought in the operator MCP integration, is at 57).
- `lessonConfig.consolidation.parameters` follows the SERVICE judge's vault rule (`ServiceJudgeVaultGrants`): a
  `${vault:...}` must name a secret granted to all agents. Checked at group save and again before each run's model call.
- `RestGroupLessons.schedule` applies `eddi.schedule.min-interval-seconds` and answers an unsatisfiable cron
  (`0 0 30 2 *`) with 400 instead of 500.
- A retirement or a merge's duplicate drop whose conditional delete loses the race is no longer reported as applied; a
  partial merge names the duplicate it kept.
- `RestScheduleStore.fireNow` requires EDIT on a consolidation schedule's group before the owner checks; an update may
  not rename a consolidation schedule (its name is how the group's endpoints find it).
- Manager: a SKIPPED run toasts a warning with the reason instead of "finished"; the schedule control sends a time zone
  (the viewer's by default) and the state badge shows the stored one; each section shows an error state when its read
  fails, instead of the empty or "not configured" text.
