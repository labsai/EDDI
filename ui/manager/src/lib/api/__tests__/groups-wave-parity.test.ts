import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  ENTRY_TYPE_INFO,
  MAX_GROUP_ATTACHMENTS,
  entryTypeInfo,
  getGroup,
  hasEnvelopeData,
  normalizeGroupConfig,
  normalizeLifecyclePolicy,
  startGroupDiscussion,
  streamGroupDiscussion,
  type AgentGroupConfiguration,
  type TranscriptEntry,
  type TranscriptEntryType,
} from "../groups";

vi.mock("../../api-client", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    getAuthHeader: vi.fn().mockReturnValue({}),
    getBaseUrl: vi.fn().mockReturnValue("http://localhost:7070"),
  },
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const api = (await import("../../api-client")).api as any;

beforeEach(() => {
  vi.clearAllMocks();
});

// A stubbed global outlives the test that installed it if an assertion throws
// first, and every later test in the worker then talks to that fetch.
afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * The regression this whole file exists for: EDDI's TranscriptEntryType grew
 * eleven values in the Wave 0 (F4) work, and the transcript was rendered by
 * indexing ENTRY_TYPE_INFO and dereferencing `.label` on the result.
 */
describe("entryTypeInfo", () => {
  it("covers every value of the backend enum", () => {
    const backendValues: TranscriptEntryType[] = [
      "QUESTION", "OPINION", "CRITIQUE", "REVISION", "CHALLENGE", "DEFENSE",
      "ARGUMENT", "REBUTTAL", "SYNTHESIS", "ERROR", "SKIPPED", "PLAN",
      "TASK_RESULT", "VERIFICATION", "FOLLOW_UP", "ABSTAINED", "DISSENT",
      "CONVERGENCE", "FACILITATION", "VOTE", "PROPOSAL", "BARGAIN",
      "HUMAN_INPUT", "RETRO", "BID",
    ];
    for (const value of backendValues) {
      expect(ENTRY_TYPE_INFO[value], value).toBeDefined();
    }
  });

  it("returns a usable label for a type this build has never heard of", () => {
    // A newer backend must degrade to an unstyled badge, not to a TypeError
    // that blanks the entire transcript.
    const info = entryTypeInfo("SOME_FUTURE_PHASE" as TranscriptEntryType);
    expect(info.label).toBe("Some Future Phase");
    expect(info.color).toBe("muted");
  });

  it("does not confuse a known type with the humanized fallback", () => {
    expect(entryTypeInfo("TASK_RESULT").label).toBe("Task Result");
    expect(entryTypeInfo("DISSENT").label).toBe("Dissent");
  });
});

/**
 * `AgentGroupConfiguration.LifecyclePolicy` is the one group enum carrying
 * Jackson's @JsonValue, so the backend writes it hyphenated and lower-case while
 * accepting either form on read.
 */
describe("normalizeLifecyclePolicy", () => {
  it("canonicalises the wire form the backend actually writes", () => {
    expect(normalizeLifecyclePolicy("ephemeral")).toBe("EPHEMERAL");
    expect(normalizeLifecyclePolicy("keep-deployed")).toBe("KEEP_DEPLOYED");
    expect(normalizeLifecyclePolicy("undeploy-only")).toBe("UNDEPLOY_ONLY");
    expect(normalizeLifecyclePolicy("agent-decides")).toBe("AGENT_DECIDES");
  });

  it("passes the canonical form through unchanged", () => {
    expect(normalizeLifecyclePolicy("KEEP_DEPLOYED")).toBe("KEEP_DEPLOYED");
  });

  it("falls back to EPHEMERAL for absent or unknown values", () => {
    expect(normalizeLifecyclePolicy(null)).toBe("EPHEMERAL");
    expect(normalizeLifecyclePolicy(undefined)).toBe("EPHEMERAL");
    expect(normalizeLifecyclePolicy("")).toBe("EPHEMERAL");
    expect(normalizeLifecyclePolicy("something-else")).toBe("EPHEMERAL");
  });
});

describe("normalizeGroupConfig", () => {
  const base = { name: "g", members: [] } as unknown as AgentGroupConfiguration;

  it("rewrites a wire-format lifecycle policy", () => {
    const out = normalizeGroupConfig({
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      dynamicAgents: { lifecyclePolicy: "keep-deployed" } as any,
    });
    expect(out.dynamicAgents!.lifecyclePolicy).toBe("KEEP_DEPLOYED");
  });

  it("returns the SAME object when nothing needs changing", () => {
    // Dirty-tracking compares by value, but a needless clone on every read is
    // still churn; the identity guarantee is documented, so it is pinned.
    const already = {
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      dynamicAgents: { lifecyclePolicy: "EPHEMERAL" } as any,
    };
    expect(normalizeGroupConfig(already)).toBe(already);

    const noDynamic = { ...base };
    expect(normalizeGroupConfig(noDynamic)).toBe(noDynamic);

    const completeProtocol = {
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      protocol: { onAgentFailure: "SKIP", onMemberUnavailable: "SKIP" } as any,
    };
    expect(normalizeGroupConfig(completeProtocol)).toBe(completeProtocol);
  });

  /**
   * The board crashed on `undefined.charAt` for any group whose stored document
   * never carried these — a group created straight over the API rather than
   * through the wizard. The type says they are required; the JSON disagrees.
   */
  it("fills the protocol member policies the backend omits", () => {
    const out = normalizeGroupConfig({
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      protocol: { agentTimeoutSeconds: 180, maxRetries: 2 } as any,
    });
    expect(out.protocol!.onAgentFailure).toBe("SKIP");
    expect(out.protocol!.onMemberUnavailable).toBe("SKIP");
    // Everything the document DID carry survives.
    expect(out.protocol!.agentTimeoutSeconds).toBe(180);
  });

  it("never overwrites a policy the document did carry", () => {
    const out = normalizeGroupConfig({
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      protocol: { onAgentFailure: "ABORT", onMemberUnavailable: "FAIL" } as any,
    });
    expect(out.protocol!.onAgentFailure).toBe("ABORT");
    expect(out.protocol!.onMemberUnavailable).toBe("FAIL");
  });

  it("leaves a config with no protocol at all alone", () => {
    const noProtocol = { ...base };
    expect(normalizeGroupConfig(noProtocol)).toBe(noProtocol);
  });

  it("fixes both a protocol and a lifecycle policy in one pass", () => {
    const out = normalizeGroupConfig({
      ...base,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      protocol: { agentTimeoutSeconds: 60 } as any,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      dynamicAgents: { lifecyclePolicy: "ephemeral" } as any,
    });
    expect(out.protocol!.onMemberUnavailable).toBe("SKIP");
    expect(out.dynamicAgents!.lifecyclePolicy).toBe("EPHEMERAL");
  });

  it("is applied by getGroup, so no consumer sees the wire form", async () => {
    api.get.mockResolvedValue({
      name: "g",
      members: [],
      dynamicAgents: { lifecyclePolicy: "agent-decides" },
    });
    const config = await getGroup("grp1", 2);
    expect(config.dynamicAgents!.lifecyclePolicy).toBe("AGENT_DECIDES");
    expect(api.get).toHaveBeenCalledWith("/groupstore/groups/grp1?version=2");
  });
});

describe("hasEnvelopeData", () => {
  const entry = (extra: Partial<TranscriptEntry>): TranscriptEntry => ({
    speakerAgentId: "a", speakerDisplayName: "A", content: "x", phaseIndex: 0,
    phaseName: "P", type: "OPINION", timestamp: "2026-01-01T00:00:00Z",
    errorReason: null, targetAgentId: null, ...extra,
  });

  it("requires signature, nonce and timestamp together", () => {
    expect(hasEnvelopeData(entry({ signature: "s", signatureNonce: "n", signatureTimestampMs: 1 }))).toBe(true);
    // A bare signature comes from an older backend: displayable, not verifiable.
    expect(hasEnvelopeData(entry({ signature: "s" }))).toBe(false);
    expect(hasEnvelopeData(entry({ signature: "s", signatureNonce: "n" }))).toBe(false);
    expect(hasEnvelopeData(entry({}))).toBe(false);
  });

  it("treats a zero timestamp as present, not as missing", () => {
    expect(hasEnvelopeData(entry({ signature: "s", signatureNonce: "n", signatureTimestampMs: 0 }))).toBe(true);
  });
});

describe("group discussion attachments", () => {
  it("omits the attachments key entirely when there are none", async () => {
    api.post.mockResolvedValue({});
    await startGroupDiscussion("g1", "hello");
    expect(api.post).toHaveBeenCalledWith("/groups/g1/conversations", {
      question: "hello",
      userId: "manager-user",
    });
  });

  it("sends inline attachments on the start endpoint", async () => {
    api.post.mockResolvedValue({});
    await startGroupDiscussion("g1", "look at this", "user-7", [
      { fileName: "a.pdf", mimeType: "application/pdf", data: "QUJD" },
    ]);
    expect(api.post).toHaveBeenCalledWith("/groups/g1/conversations", {
      question: "look at this",
      userId: "user-7",
      attachments: [{ fileName: "a.pdf", mimeType: "application/pdf", data: "QUJD" }],
    });
  });

  it("sends them on the streaming start endpoint too", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: async () => ({ done: true }), releaseLock() {} }) },
    });
    vi.stubGlobal("fetch", fetchMock);

    const gen = streamGroupDiscussion("g1", "q", undefined, undefined, [
      { fileName: "b.png", mimeType: "image/png", data: "Zm9v" },
    ]);
    await gen.next();

    const body = JSON.parse(fetchMock.mock.calls[0]![1].body);
    expect(body.attachments).toEqual([
      { fileName: "b.png", mimeType: "image/png", data: "Zm9v" },
    ]);
  });

  it("exposes the backend's per-request ceiling", () => {
    expect(MAX_GROUP_ATTACHMENTS).toBe(50);
  });
});
