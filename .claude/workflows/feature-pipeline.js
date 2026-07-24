export const meta = {
  name: 'feature-pipeline',
  description: 'Explore -> plan/decompose -> parallel worktree implementers -> merge -> high-effort review',
  whenToUse: 'Use for a substantial coding task that can be split into independent workstreams. Runs three explorers, a planner that decomposes the task, one implementer per workstream in its own git worktree, a merge step that combines the branches, and a final high-effort review. Does NOT open a PR — review the result and push/PR yourself.',
  phases: [
    { title: 'Explore' },
    { title: 'Plan' },
    { title: 'Implement' },
    { title: 'Merge' },
    { title: 'Review' },
  ],
}

const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    workstreams: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          instructions: { type: 'string' },
        },
        required: ['id', 'title', 'instructions'],
      },
    },
  },
  required: ['workstreams'],
}

const IMPLEMENT_SCHEMA = {
  type: 'object',
  properties: {
    workstream_id: { type: 'string' },
    branch: { type: 'string' },
    worktree_path: { type: 'string' },
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
  },
  required: ['workstream_id', 'branch', 'worktree_path', 'summary'],
}

const MERGE_SCHEMA = {
  type: 'object',
  properties: {
    integrationBranch: { type: 'string' },
    merged: { type: 'array', items: { type: 'string' } },
    conflicts: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
  },
  required: ['integrationBranch', 'merged', 'conflicts'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          summary: { type: 'string' },
          severity: { type: 'string' },
        },
        required: ['file', 'summary'],
      },
    },
    verdict: { type: 'string' },
  },
  required: ['findings', 'verdict'],
}

if (!args || !args.task) {
  throw new Error('feature-pipeline requires args.task — a description of the coding task to implement')
}

phase('Explore')
const explorations = (await parallel([
  () => agent(
    `Explore the codebase to understand everything relevant to this task: ${args.task}. Report the key files, patterns, and conventions to follow. Be concrete with file paths.`,
    { agentType: 'Explore', label: 'explore:general' }
  ),
  () => agent(
    `Explore the codebase specifically for existing tests and test conventions relevant to: ${args.task}`,
    { agentType: 'Explore', label: 'explore:tests' }
  ),
  () => agent(
    `Explore the codebase for existing features/patterns similar to: ${args.task}. We want to follow existing conventions, not invent new ones.`,
    { agentType: 'Explore', label: 'explore:conventions' }
  ),
])).filter(Boolean)

phase('Plan')
const plan = await agent(
  `You are planning implementation of this task:\n\n${args.task}\n\nHere is what exploration agents found:\n\n${explorations.join('\n\n---\n\n')}\n\nDecompose the task into 2-4 INDEPENDENT workstreams that can be implemented in parallel by different engineers without touching the same files or blocking on each other. If the task is small/atomic and cannot be meaningfully split, return a single workstream. For each workstream give a clear id, title, and precise instructions (files to touch, what to build, acceptance criteria, project conventions to follow).`,
  { schema: PLAN_SCHEMA, effort: 'high', label: 'plan' }
)

log(`Planned ${plan.workstreams.length} workstream(s): ${plan.workstreams.map(w => w.title).join(', ')}`)

phase('Implement')
const implementations = (await parallel(plan.workstreams.map(w => () =>
  agent(
    `Implement this workstream as part of a larger task. Follow this project's AGENTS.md/CLAUDE.md conventions.\n\nWorkstream: ${w.title}\nInstructions: ${w.instructions}\n\nFull task context: ${args.task}\n\nWork ONLY within the scope of this workstream so it can be merged independently. Commit your changes with a conventional commit message when done. After committing, report the exact current branch name (git branch --show-current) and the absolute worktree path (git rev-parse --show-toplevel).`,
    { isolation: 'worktree', schema: IMPLEMENT_SCHEMA, label: `implement:${w.id}`, phase: 'Implement' }
  ).then(r => r && ({ ...r, workstream: w }))
))).filter(Boolean)

if (!implementations.length) {
  return { error: 'No workstream produced a result', plan }
}

phase('Merge')
const mergeResult = await agent(
  `Merge these independently-implemented git branches together into one integration branch, in THIS repo checkout (not a worktree).\n\nSteps:\n1. Create a new branch off the current HEAD named integration/<short-task-slug>.\n2. For each branch below, run 'git merge --no-ff <branch>'.\n3. If a merge conflict occurs, resolve it by reading both sides' intent (described below) and combining them correctly — do not blindly take one side. If truly unresolvable, abort that merge (git merge --abort) and report it as an unresolved conflict rather than leaving the repo broken.\n4. Report the integration branch name, which branches merged cleanly, and which had conflicts.\n\nBranches to merge:\n${implementations.map(i => `- ${i.branch} (${i.workstream.title}): ${i.summary}`).join('\n')}`,
  { schema: MERGE_SCHEMA, label: 'merge', effort: 'high' }
)

phase('Review')
const review = await agent(
  `Review branch '${mergeResult.integrationBranch}' against the base branch it diverged from. Task being implemented: ${args.task}. Look for correctness bugs, integration issues between the merged workstreams (mismatched interfaces, duplicated logic, missed wiring), and violations of this project's AGENTS.md/CLAUDE.md conventions. Report findings ranked by severity, most severe first, and give an overall verdict: 'ready', 'needs_fixes', or 'blocked'.`,
  { schema: REVIEW_SCHEMA, effort: 'max', label: 'review' }
)

return { plan, implementations, merge: mergeResult, review }
