## 📝 docs(planning): evaluated generation — checks and repair for every step that produces something (2026-10-24)

**Repo:** EDDI (`docs/evaluated-generation-plan`)

### What changed and why

New planning document [`planning/evaluated-generation-plan.md`](../../planning/evaluated-generation-plan.md). No code changes.

It started as an analysis of whether EDDI could host a production AI video-generation pipeline: about 150 model calls per job, best-of-N keyframes, multimodal judges, a repair ladder, a human cost gate, and ffmpeg assembly. The answer generalises beyond video. **Make → judge → repair** is the loop behind grounded RAG answers, extraction, text-to-SQL, output compliance, content production, group deliverables and media.

Verified against `main` at `5028af0a3`, EDDI already runs that loop in seven places:
- output guardrails (#1064, with the `classifier` type from #1112);
- format recovery (R5);
- the model cascade;
- TASK_FORCE VERIFY, which has no rework within a run;
- group task approval;
- single-agent HITL;
- team cadences.

The plan builds on them instead of adding an eighth:

| Item | What it adds |
| --- | --- |
| E1 | A `judge` output guardrail that answers with author-declared issue codes, so a reprompt says what to fix; `onExhausted: deliver-best` for quality checks, so the best attempt is delivered (still screened by every other guardrail) instead of a block message |
| E1c | A deterministic `expression` guardrail |
| E2 | Rework after a failed verdict, for humans and groups |
| E4 | Spend checks that see every attempt, and reservations for parallel calls |
| E5 | Checks and `forEach` on tool and HTTP steps |
| E6 | Media groundwork |
| E7 | Speech and image providers |
| E8 | Long jobs through self-continuation and parked operations |
| E9 | A fingerprinted DAG |
| E10 | A composition service outside core |

### Design decisions

- **Checks on meaning are output guardrails**, not a new block in `responseValidation`. Guardrails already run after it in every execution mode, with their own reprompt loop, cost metering and `failMode`.
- **`judge` is a sibling of `classifier`, not a mode of it.** It shares the classifier's model-call machinery: provider pass-through, timeouts, deadline, pricing and `promptTemplate` data. Its contract differs: a structured list of codes rather than "safe is a positive label".
- **Judge feedback uses a closed vocabulary.** `GuardrailService.correctiveMessage` never puts the user's or the model's text into a reprompt, so the judge returns only declared codes, and the producer is told only the designer's fix text for them.
- **The pipeline stays a straight line.** Loops live inside a step, and repairs that must reach an earlier step leave as actions.
- **ffmpeg, music and domain-specific logic stay outside core.**

```decision-log
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
```
