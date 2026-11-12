import { describe, expect, it } from "vitest";
import {
  humanizeToolName,
  parseToolCallEvent,
  toolArgumentFields,
  toolCallActivity,
} from "@/lib/tool-labels";
import { parseToolArgumentsForDisplay } from "@/lib/hitl-format-args";

describe("humanizeToolName", () => {
  it.each([
    // OpenAPI operationIds the Platform Operator's generated tools carry.
    ["getAgentDescriptors", "Get agent descriptors"],
    ["updateResourceUri", "Update resource uri"],
    ["cascadeAgentChanges", "Cascade agent changes"],
    // MCP snake_case and httpcall names.
    ["add_issue_comment", "Add issue comment"],
    ["apicalls_weather_call", "Apicalls weather call"],
    // Acronyms stay whole.
    ["fetchHTTPStatus", "Fetch HTTP status"],
    ["getURL", "Get URL"],
    // Separators.
    ["rag.search", "Rag search"],
    ["web-search", "Web search"],
    ["webSearch", "Web search"],
    ["calculator", "Calculator"],
  ])("%s → %s", (name, expected) => {
    expect(humanizeToolName(name)).toBe(expected);
  });

  it("leaves a designer label (text with spaces) exactly as written", () => {
    expect(humanizeToolName("Creating an action: Renew the contract")).toBe(
      "Creating an action: Renew the contract",
    );
  });

  it("returns empty input unchanged", () => {
    expect(humanizeToolName("")).toBe("");
    expect(humanizeToolName(undefined)).toBe("");
    expect(humanizeToolName("___")).toBe("___");
  });
});

describe("parseToolCallEvent / toolCallActivity", () => {
  // RestAgentEngineStreaming.ToolCallEvent: {tool, callId, displayName, display}
  it("uses the toolDisplay label and allow-listed values when the designer configured them", () => {
    const call = parseToolCallEvent(
      JSON.stringify({ tool: "createAction", callId: "call_1", displayName: "Creating an action", display: { title: "Renew the contract" } }),
    );
    expect(call).toEqual({ tool: "createAction", displayName: "Creating an action", display: { title: "Renew the contract" } });
    expect(toolCallActivity(call!)).toEqual({ text: "Creating an action: Renew the contract", phrase: true });
  });

  it("keeps the identifier for a plain event, for the renderer to humanise (null fields are serialised by Jackson)", () => {
    const call = parseToolCallEvent('{"tool":"getAgentDescriptors","callId":"call_2","displayName":null,"display":null}');
    expect(toolCallActivity(call!)).toEqual({ text: "getAgentDescriptors", phrase: false });
  });

  it("accepts the pre-6.7 name-only event", () => {
    expect(toolCallActivity(parseToolCallEvent('{"tool":"webSearch"}')!)).toEqual({ text: "webSearch", phrase: false });
  });

  it("labels allow-listed values without a configured label by the humanised name", () => {
    const call = parseToolCallEvent('{"tool":"send_mail","callId":"c","display":{"to":"ops@acme.com"}}');
    expect(toolCallActivity(call!)).toEqual({ text: "Send mail: ops@acme.com", phrase: true });
  });

  it("marks a one-word designer label as a phrase, whatever its shape", () => {
    const call = parseToolCallEvent('{"tool":"webSearch","callId":"c","displayName":"Searching"}');
    expect(toolCallActivity(call!)).toEqual({ text: "Searching", phrase: true });
  });

  it.each(["github.search-code", "server/tool-name", "weather:forecast", "Calc2"])(
    "keeps %s an identifier",
    (tool) => {
      expect(toolCallActivity(parseToolCallEvent(JSON.stringify({ tool, callId: "c" }))!)).toEqual({
        text: tool,
        phrase: false,
      });
    },
  );

  it("rejects a frame without a usable tool", () => {
    expect(parseToolCallEvent("")).toBeNull();
    expect(parseToolCallEvent("not json")).toBeNull();
    expect(parseToolCallEvent('{"tool":""}')).toBeNull();
    expect(parseToolCallEvent('{"callId":"x"}')).toBeNull();
  });
});

describe("toolArgumentFields", () => {
  it("renders the Platform Operator's nested requestBody as nested rows", () => {
    const args = JSON.stringify({ requestBody: JSON.stringify({ agentName: "Support", apiKey: "<REDACTED>", tags: ["a", "b"] }) });
    const fields = toolArgumentFields(parseToolArgumentsForDisplay(args));
    expect(fields).toEqual([
      {
        key: "requestBody",
        label: "Request body",
        value: [
          { key: "agentName", label: "Agent name", value: "Support" },
          { key: "apiKey", label: "Api key", value: "<REDACTED>" },
          { key: "tags", label: "Tags", value: "a, b" },
        ],
      },
    ]);
  });

  it("shows scalars as text and arrays of objects as JSON", () => {
    const fields = toolArgumentFields(parseToolArgumentsForDisplay('{"amount":100,"dryRun":false,"items":[{"id":1}]}'));
    expect(fields?.map((f) => [f.label, f.value, f.json ?? false])).toEqual([
      ["Amount", "100", false],
      ["Dry run", "false", false],
      ["Items", '[\n  {\n    "id": 1\n  }\n]', true],
    ]);
  });

  it("is null when the arguments cannot be shown honestly as rows", () => {
    expect(toolArgumentFields(parseToolArgumentsForDisplay('{"a":1', true))).toBeNull();
    expect(toolArgumentFields(parseToolArgumentsForDisplay("not json"))).toBeNull();
    expect(toolArgumentFields(parseToolArgumentsForDisplay("[1,2]"))).toBeNull();
    expect(toolArgumentFields(parseToolArgumentsForDisplay("{}"))).toBeNull();
    // Lossy JSON (a number past 2^53) is never re-rendered.
    expect(toolArgumentFields(parseToolArgumentsForDisplay('{"id":12345678901234567890}'))).toBeNull();
  });
});
