# Changing an agent safely

Every EDDI configuration document is versioned and immutable once saved: an update
writes a new version. An agent points at workflows, a workflow points at resources, each
by a URI that carries a version (`eddi://ai.labs.llm/llmstore/llms/<id>?version=3`). So a
change to one resource is only live once the workflow and the agent point at its new
version and that agent version is deployed. This is the order that does it without
leaving a trail of half-wired versions.

## 1. Read

1. `list_agent_resources` with the `agentId` — note the agent version, and the type, id,
   version and URI of every resource you will touch.
2. `read_resource` with `type`, `id` and `version` for each one. Keep the JSON as read:
   `update_resource` takes the **full** document, not a patch. The `type` names are the
   tools' own, not the URI's: `behavior` (rules), `langchain` (LLM tasks), `httpcalls`,
   `mcpcalls`, `output`, `propertysetter`, `dictionaries`, and `rag` (read only).

**What MCP cannot change.** The tools edit the documents a workflow points at; they do
not edit a workflow itself. Adding or removing a step — a new HTTP-call set, an MCP-call
step, a knowledge base, the templating step — and changing a knowledge base are done in
the Manager or over REST. Say so instead of attempting it, and offer the parts you can
do: create the resource with `create_resource`, then hand over its URI.

If the user's change is vague ("make it friendlier"), restate it as a concrete edit and
confirm before writing.

## 2. Write each changed resource

`update_resource` with `type`, `id`, the **current** `version` and the full edited
`config`. Each call returns the new version URI. Record `old URI → new URI` for each.

- Only the latest version of a document can be updated. A refusal saying "Resource
  already modified" means someone else saved in between — read again and redo the edit on top.
- Before writing rules, output, property or HTTP-call JSON by hand, check
  [config-gotchas.md](config-gotchas.md).

## 3. Wire the new versions in — one call

`apply_agent_changes` with:

- `agentId` and the current `agentVersion`,
- `resourceMappings`: a JSON array of every `{"oldUri": "...", "newUri": "..."}` from step 2,
- `redeploy`: `true` with `environment` `test`, so the result is ready to try,
- `compatible`: see below.

It rewrites every workflow and the agent once. Do **not** cascade resource by resource;
each call would mint another agent version. `update_agent` is for name and description
only.

### `compatible` — ask when unsure

It decides what happens to conversations already running on the old version once the new
one is deployed:

| `compatible` | Running conversations | Use for |
| --- | --- | --- |
| `true` | Move to the new version on their next turn | Changes that cannot break a conversation in progress: prompt wording, a different model, a new output text |
| `false` (default) | Stay on the version they started on | Renamed or removed actions or properties, new required conversation state, a changed flow of rules |

`false` is not automatically the cautious choice: old conversations keep running on the
old version, so they never get the fix, and that version has to stay deployed until
they finish (or someone ends them). If the change is
safe for running conversations, `true` is what keeps users on the fixed agent. If you
cannot tell, explain both outcomes and let the user choose.

## 4. Verify in `test`

- Talk to it: `chat_with_agent` with `environment` `test`, using the inputs that showed
  the problem and a couple that worked before.
- If the agent has a test suite, `run_agent_tests` and wait for `get_agent_test_run`.
- If it misbehaves, debug with [debugging.md](debugging.md) and start again from step 1 —
  the new versions are now the current ones.

## 5. Promote — with the user's yes

`deploy_agent` with the new `version` and `environment` `production`, after saying which
version is going live and what changed. Check with `get_deployment_status`.

## 6. Old versions

Leave them deployed unless the user asks to clean up.

- `undeploy_agent` refuses while the version still has open conversations — that refusal
  protects users. A compatible newer version moves them; check before undeploying.
- `endConversations` `true` ends users' conversations mid-flow. Use it only when the
  user explicitly wants those conversations ended, never just to make an undeploy pass.

`read_docs("deployment-management-of-agents")` covers deployment and compatibility in
full.
