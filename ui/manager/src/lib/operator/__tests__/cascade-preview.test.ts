import { describe, it, expect } from "vitest";
import { isCascadeRequest, parseCascadePreview } from "../cascade-preview";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

const AGENT = "68f1c2a9b34d5e6f70819a2b";
const LLM = "112233445566778899aabbcc";
const WF = "ff00112233445566aa770011";

const llm = (v: number) => `eddi://ai.labs.llm/llmstore/llms/${LLM}?version=${v}`;
const wf = (v: number) => `eddi://ai.labs.workflow/workflowstore/workflows/${WF}?version=${v}`;

function preview(body: unknown, overrides: Partial<ResolvedRequestPreview> = {}): ResolvedRequestPreview {
  return {
    method: "POST",
    uri: `https://eddi.example/agentstore/agents/${AGENT}/cascade?version=4`,
    queryParams: { version: "4" },
    headers: {},
    body: typeof body === "string" ? body : JSON.stringify(body),
    bodyTruncated: false,
    ...overrides,
  };
}

describe("isCascadeRequest", () => {
  it("recognises the cascade endpoint by method and path", () => {
    expect(isCascadeRequest(preview({}))).toBe(true);
  });

  it("is not fooled by another verb or another sub-resource", () => {
    expect(isCascadeRequest(preview({}, { method: "PUT" }))).toBe(false);
    expect(
      isCascadeRequest(preview({}, { uri: `https://eddi.example/agentstore/agents/${AGENT}/updateResourceUri?version=4` })),
    ).toBe(false);
    expect(isCascadeRequest(preview({}, { uri: `https://eddi.example/agentstore/agents/${AGENT}?version=4` }))).toBe(false);
    expect(isCascadeRequest(null)).toBe(false);
  });
});

describe("parseCascadePreview", () => {
  it("reduces each mapping to kind, id and the version change", () => {
    const parsed = parseCascadePreview(
      preview({
        resourceMappings: [
          { oldUri: llm(1), newUri: llm(2) },
          { oldUri: wf(3), newUri: wf(5) },
        ],
        redeploy: true,
        environment: "test",
        compatible: true,
      }),
    )!;

    expect(parsed.agentId).toBe(AGENT);
    expect(parsed.agentVersion).toBe(4);
    expect(parsed.mappings).toHaveLength(2);
    expect(parsed.mappings[0]).toMatchObject({ kind: "llm", id: LLM, fromVersion: 1, toVersion: 2, newId: null });
    expect(parsed.mappings[1]).toMatchObject({ kind: "workflow", id: WF, fromVersion: 3, toVersion: 5 });
    expect(parsed.redeploy).toBe(true);
    expect(parsed.environment).toBe("test");
    expect(parsed.compatible).toBe(true);
  });

  it("defaults the way the backend does: no redeploy, production, a breaking change", () => {
    const parsed = parseCascadePreview(preview({ resourceMappings: [{ oldUri: llm(1), newUri: llm(2) }] }))!;
    expect(parsed.redeploy).toBe(false);
    expect(parsed.environment).toBe("production");
    expect(parsed.compatible).toBe(false);
  });

  it("flags a mapping that swaps to a different resource rather than a newer version", () => {
    const other = "99887766554433221100aabb";
    const parsed = parseCascadePreview(
      preview({
        resourceMappings: [{ oldUri: llm(1), newUri: `eddi://ai.labs.llm/llmstore/llms/${other}?version=1` }],
      }),
    )!;
    expect(parsed.mappings[0]!.newId).toBe(other);
  });

  it("returns null rather than a partial summary for a truncated body", () => {
    const body = JSON.stringify({ resourceMappings: [{ oldUri: llm(1), newUri: llm(2) }] });
    expect(parseCascadePreview(preview(body.slice(0, 40), { bodyTruncated: true }))).toBeNull();
  });

  it("returns null for a body that is not the expected shape, never a summary missing a mapping", () => {
    expect(parseCascadePreview(preview("not json"))).toBeNull();
    expect(parseCascadePreview(preview({}))).toBeNull();
    expect(parseCascadePreview(preview({ resourceMappings: [] }))).toBeNull();
    // ONE unreadable entry withdraws the whole summary: a list that skips an entry reads as complete.
    expect(
      parseCascadePreview(preview({ resourceMappings: [{ oldUri: llm(1), newUri: llm(2) }, { oldUri: 7, newUri: llm(2) }] })),
    ).toBeNull();
    expect(parseCascadePreview(preview({ resourceMappings: [null] }))).toBeNull();
  });

  it("returns null for a request that is not a cascade", () => {
    expect(
      parseCascadePreview(preview({ resourceMappings: [] }, { uri: "https://eddi.example/agentstore/agents" })),
    ).toBeNull();
  });

  it("shows an unparseable URI as written instead of inventing a kind", () => {
    const parsed = parseCascadePreview(preview({ resourceMappings: [{ oldUri: "weird", newUri: "weirder" }] }))!;
    expect(parsed.mappings[0]).toMatchObject({ kind: "resource", id: "weird", fromVersion: null, toVersion: null });
  });
});
